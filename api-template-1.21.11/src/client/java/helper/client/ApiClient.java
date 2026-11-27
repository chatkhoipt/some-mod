package helper.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

public class ApiClient implements ClientModInitializer {
    // CHANGE THIS to your own secret. Anyone without it gets nothing.
    private static final String KEY = "daukhoi";
    // "127.0.0.1" = this PC only. Use "0.0.0.0" to allow phones/other PCs on your Wi-Fi.
    private static final String BIND = "127.0.0.1";
    private static final int PORT = 8080;

    private static volatile String players = "{\"players\":[]}";
    private static volatile String status = "{\"online\":false}";
    private static final ArrayDeque<String> chat = new ArrayDeque<>();

    private static void addChat(String s) {
        synchronized (chat) {
            chat.addLast(s);
            while (chat.size() > 100) chat.removeFirst();
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static String param(HttpExchange ex, String name) {
        String qs = ex.getRequestURI().getRawQuery();
        if (qs == null) return "";
        for (String part : qs.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) {
                return URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) addChat(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
            addChat(message.getString()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                status = "{\"online\":false}";
                return;
            }
            if (client.level.getGameTime() % 10 != 0) return;

            StringBuilder sb = new StringBuilder("{\"players\":[");
            boolean first = true;
            for (AbstractClientPlayer p : client.level.players()) {
                if (p == client.player) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"distance\":%.1f}",
                    esc(p.getName().getString()), p.getX(), p.getY(), p.getZ(),
                    p.distanceTo(client.player)));
            }
            players = sb.append("]}").toString();

            status = String.format(Locale.ROOT,
                "{\"online\":true,\"name\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"health\":%.1f,\"food\":%d}",
                esc(client.player.getName().getString()),
                client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getHealth(), client.player.getFoodData().getFoodLevel());
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(BIND, PORT), 0);
            server.createContext("/", ex -> {
                try {
                    if (!KEY.equals(param(ex, "key"))) {
                        reply(ex, 403, "text/plain", "forbidden");
                        return;
                    }
                    String path = ex.getRequestURI().getPath();
                    String method = ex.getRequestMethod();
                    Minecraft mc = Minecraft.getInstance();

                    if (path.equals("/")) {
                        reply(ex, 200, "text/html", PANEL);
                    } else if (path.equals("/api/status")) {
                        reply(ex, 200, "application/json", status);
                    } else if (path.equals("/api/players")) {
                        reply(ex, 200, "application/json", players);
                    } else if (path.equals("/api/chat") && method.equals("GET")) {
                        StringBuilder sb = new StringBuilder("{\"messages\":[");
                        synchronized (chat) {
                            boolean first = true;
                            for (String m : chat) {
                                if (!first) sb.append(',');
                                first = false;
                                sb.append('"').append(esc(m)).append('"');
                            }
                        }
                        reply(ex, 200, "application/json", sb.append("]}").toString());
                    } else if ((path.equals("/api/chat") || path.equals("/api/command")) && method.equals("POST")) {
                        String text = new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8).trim();
                        if (text.isEmpty()) {
                            reply(ex, 400, "text/plain", "empty");
                            return;
                        }
                        boolean isCmd = path.equals("/api/command");
                        mc.execute(() -> {
                            if (mc.player == null) return;
                            if (isCmd) mc.player.connection.sendCommand(text.startsWith("/") ? text.substring(1) : text);
                            else mc.player.connection.sendChat(text);
                        });
                        reply(ex, 200, "application/json", "{\"ok\":true}");
                    } else {
                        reply(ex, 404, "text/plain", "not found");
                    }
                } catch (Exception e) {
                    reply(ex, 500, "text/plain", "error");
                }
            });
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final String PANEL = """
        <!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
        <title>MC Panel</title>
        <style>
        body{font-family:sans-serif;background:#111;color:#eee;margin:1rem;max-width:700px}
        pre{height:260px;overflow:auto;background:#000;padding:8px;white-space:pre-wrap}
        input{width:72%;padding:6px} button{padding:6px 12px}
        </style>
        <h2>MC Panel</h2>
        <div id=st>...</div>
        <h3>Nearby players</h3><ul id=pl></ul>
        <h3>Chat</h3><pre id=chat></pre>
        <input id=msg placeholder="message, or /command"> <button onclick=send()>Send</button>
        <script>
        const key = new URLSearchParams(location.search).get('key') || '';
        const q = '?key=' + encodeURIComponent(key);
        async function get(p){ const r = await fetch(p + q); return r.json(); }
        async function poll(){
          try {
            const s = await get('/api/status');
            document.getElementById('st').textContent = s.online
              ? s.name + ' | ' + s.x + ', ' + s.y + ', ' + s.z + ' | HP ' + s.health + ' | Food ' + s.food
              : 'Not in a world';
            const p = await get('/api/players');
            const ul = document.getElementById('pl'); ul.textContent = '';
            p.players.forEach(x => {
              const li = document.createElement('li');
              li.textContent = x.name + ' (' + x.distance + ' blocks) at ' + x.x + ', ' + x.y + ', ' + x.z;
              ul.appendChild(li);
            });
            const c = await get('/api/chat');
            const box = document.getElementById('chat');
            const atEnd = box.scrollTop + box.clientHeight >= box.scrollHeight - 20;
            box.textContent = c.messages.join(String.fromCharCode(10));
            if (atEnd) box.scrollTop = box.scrollHeight;
          } catch (e) {}
        }
        async function send(){
          const i = document.getElementById('msg'); const t = i.value.trim(); if (!t) return;
          const isCmd = t.startsWith('/');
          await fetch((isCmd ? '/api/command' : '/api/chat') + q, {method:'POST', body: isCmd ? t.slice(1) : t});
          i.value = '';
        }
        document.getElementById('msg').addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
        setInterval(poll, 1000); poll();
        </script>
        """;
}
