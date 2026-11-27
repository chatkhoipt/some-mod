package helper.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ApiClient implements ClientModInitializer {
    // CHANGE THIS to your own secret. Anyone without it gets nothing.
    private static final String KEY = "daukhoi";
    // "127.0.0.1" = this PC only. Use "0.0.0.0" to allow phones/other PCs on your Wi-Fi.
    private static final String BIND = "127.0.0.1";
    private static final int PORT = 8080;

    private static volatile String players = "{\"players\":[]}";
    private static volatile String status = "{\"online\":false}";
    private static volatile String menuJson = "{\"open\":false}";
    private static final ArrayDeque<String> chat = new ArrayDeque<>();
    private static int ticks = 0;

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

    private static String item(ItemStack s) {
        if (s.isEmpty()) return "null";
        return "\"" + esc(s.getHoverName().getString()) + " x" + s.getCount() + "\"";
    }

    private static String buildMenu(Minecraft client) {
        var m = client.player.containerMenu;
        if (m == client.player.inventoryMenu) return "{\"open\":false}";
        int invStart = m.slots.size();
        for (int i = 0; i < m.slots.size(); i++) {
            if (m.slots.get(i).container == client.player.getInventory()) { invStart = i; break; }
        }
        StringBuilder sb = new StringBuilder("{\"open\":true,\"type\":\"")
            .append(esc(m.getClass().getSimpleName())).append("\",\"invStart\":").append(invStart)
            .append(",\"carried\":").append(item(m.getCarried())).append(",\"slots\":[");
        for (int i = 0; i < m.slots.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(item(m.slots.get(i).getItem()));
        }
        sb.append("]");
        if (m instanceof MerchantMenu mm) {
            sb.append(",\"trades\":[");
            boolean first = true;
            for (MerchantOffer o : mm.getOffers()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"a\":").append(item(o.getBaseCostA()))
                  .append(",\"b\":").append(item(o.getCostB()))
                  .append(",\"result\":").append(item(o.getResult()))
                  .append(",\"out\":").append(o.isOutOfStock()).append("}");
            }
            sb.append("]");
        }
        if (m instanceof EnchantmentMenu em) {
            sb.append(",\"enchantCosts\":[").append(em.costs[0]).append(',')
              .append(em.costs[1]).append(',').append(em.costs[2]).append("]");
        }
        return sb.append("}").toString();
    }

    // ---------- opening GUIs remotely (runs on the client thread) ----------

    private static String openVillager(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return "not in a world";
        Entity best = null;
        double bd = 1e9;
        for (Entity v : mc.level.getEntitiesOfClass(Entity.class,
                mc.player.getBoundingBox().inflate(8))) {
            if (v.getType() != EntityType.VILLAGER && v.getType() != EntityType.WANDERING_TRADER) continue;
            double d = mc.player.distanceTo(v);
            if (d < bd) { bd = d; best = v; }
        }
        if (best == null) return "no villager within 8 blocks";
        if (bd > 3.5) return String.format(Locale.ROOT,
            "nearest villager is %.1f blocks away, get within 3 blocks", bd);
        mc.gameMode.interact(mc.player, best, InteractionHand.MAIN_HAND);
        return "opening villager trade";
    }

    private static String useBlock(Minecraft mc, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        if (mc.player.getEyePosition().distanceTo(c) > 4.5) return "block is too far, get within 4 blocks";
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(c, Direction.UP, pos, false));
        return "opening block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String openNearest(Minecraft mc, Block target, String label) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return "not in a world";
        BlockPos origin = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bd = 1e18;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (mc.level.getBlockState(p).is(target)) {
                        double d = eye.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bd) { bd = d; best = p; }
                    }
                }
            }
        }
        if (best == null) return "no " + label + " within 6 blocks";
        return useBlock(mc, best);
    }

    private static String runOnClient(Minecraft mc, Supplier<String> task) {
        try {
            return mc.submit(task).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    // ---------- http helpers ----------

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

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8).trim();
    }

    private static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    private static void msg(HttpExchange ex, String m) throws IOException {
        reply(ex, 200, "application/json", "{\"msg\":\"" + esc(m) + "\"}");
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
                menuJson = "{\"open\":false}";
                return;
            }
            ticks++;

            if (ticks % 5 == 0) {
                menuJson = buildMenu(client);
            }

            if (ticks % 10 == 0) {
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
            }
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
                    } else if (path.equals("/api/menu")) {
                        reply(ex, 200, "application/json", menuJson);
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
                        String text = body(ex);
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

                    // ----- goto / stop (needs the Baritone mod installed) -----
                    } else if (path.equals("/api/goto") && method.equals("POST")) {
                        String[] n = body(ex).split("[ ,]+");
                        String cmd;
                        try {
                            if (n.length == 3) cmd = "#goto " + Integer.parseInt(n[0]) + " " + Integer.parseInt(n[1]) + " " + Integer.parseInt(n[2]);
                            else if (n.length == 2) cmd = "#goto " + Integer.parseInt(n[0]) + " " + Integer.parseInt(n[1]);
                            else { msg(ex, "use x y z  or  x z"); return; }
                        } catch (NumberFormatException e) { msg(ex, "coordinates must be whole numbers"); return; }
                        final String c = cmd;
                        mc.execute(() -> { if (mc.player != null) mc.player.connection.sendChat(c); });
                        msg(ex, "sent " + c);
                    } else if (path.equals("/api/stop") && method.equals("POST")) {
                        mc.execute(() -> { if (mc.player != null) mc.player.connection.sendChat("#stop"); });
                        msg(ex, "sent #stop");

                    // ----- open GUIs remotely -----
                    } else if (path.equals("/api/open/villager") && method.equals("POST")) {
                        msg(ex, runOnClient(mc, () -> openVillager(mc)));
                    } else if (path.equals("/api/open/enchant") && method.equals("POST")) {
                        msg(ex, runOnClient(mc, () -> openNearest(mc, Blocks.ENCHANTING_TABLE, "enchanting table")));
                    } else if (path.equals("/api/open/grindstone") && method.equals("POST")) {
                        msg(ex, runOnClient(mc, () -> openNearest(mc, Blocks.GRINDSTONE, "grindstone")));
                    } else if (path.equals("/api/open/block") && method.equals("POST")) {
                        String[] n = body(ex).split("[ ,]+");
                        BlockPos pos;
                        try {
                            pos = new BlockPos(Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]));
                        } catch (Exception e) { msg(ex, "use x y z"); return; }
                        final BlockPos target = pos;
                        msg(ex, runOnClient(mc, () -> mc.player == null || mc.gameMode == null
                            ? "not in a world" : useBlock(mc, target)));

                    // ----- clicking inside an open GUI -----
                    } else if (method.equals("POST") && path.startsWith("/api/gui/")) {
                        String[] a = body(ex).split(",");
                        mc.execute(() -> {
                            if (mc.player == null || mc.gameMode == null) return;
                            var m = mc.player.containerMenu;
                            if (m == mc.player.inventoryMenu) return;
                            try {
                                if (path.equals("/api/gui/click")) {
                                    // body: slot,button,shift  (e.g. "2,0,1")
                                    ClickType t = a[2].equals("1") ? ClickType.QUICK_MOVE : ClickType.PICKUP;
                                    mc.gameMode.handleInventoryMouseClick(m.containerId,
                                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), t, mc.player);
                                } else if (path.equals("/api/gui/trade") && m instanceof MerchantMenu mm) {
                                    int i = Integer.parseInt(a[0]);
                                    mm.setSelectionHint(i);
                                    mm.tryMoveItems(i);
                                    mc.player.connection.send(new ServerboundSelectTradePacket(i));
                                } else if (path.equals("/api/gui/enchant")) {
                                    mc.gameMode.handleInventoryButtonClick(m.containerId, Integer.parseInt(a[0]));
                                } else if (path.equals("/api/gui/close")) {
                                    mc.player.closeContainer();
                                }
                            } catch (Exception ignored) {}
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
        body{font-family:sans-serif;background:#111;color:#eee;margin:1rem;max-width:760px}
        pre{height:220px;overflow:auto;background:#000;padding:8px;white-space:pre-wrap}
        input{padding:6px} button{padding:6px 12px;margin:2px}
        #gui button{font-size:12px;padding:4px 8px}
        .empty{opacity:.45}
        #act{color:#8f8;min-height:1.2em}
        </style>
        <h2>MC Panel</h2>
        <div id=st>...</div>

        <h3>Go to</h3>
        <input id=gx size=6 placeholder=x> <input id=gy size=6 placeholder="y (optional)"> <input id=gz size=6 placeholder=z>
        <button onclick=go()>Go</button> <button onclick="act('/api/stop','')">Stop</button>

        <h3>Open</h3>
        <button onclick="act('/api/open/villager','')">Nearest villager</button>
        <button onclick="act('/api/open/enchant','')">Enchanting table</button>
        <button onclick="act('/api/open/grindstone','')">Grindstone</button>
        <div id=act></div>

        <h3>Open GUI</h3><div id=gui>none</div>
        <h3>Nearby players</h3><ul id=pl></ul>
        <h3>Chat</h3><pre id=chat></pre>
        <input id=msg style="width:70%" placeholder="message, or /command"> <button onclick=send()>Send</button>
        <script>
        const key = new URLSearchParams(location.search).get('key') || '';
        const q = '?key=' + encodeURIComponent(key);
        async function get(p){ const r = await fetch(p + q); return r.json(); }
        async function post(p, body){ const r = await fetch(p + q, {method:'POST', body}); return r.json(); }
        async function act(p, body){
          const r = await post(p, body);
          document.getElementById('act').textContent = r.msg || 'ok';
        }
        function go(){
          const x = document.getElementById('gx').value.trim();
          const y = document.getElementById('gy').value.trim();
          const z = document.getElementById('gz').value.trim();
          if (!x || !z) { document.getElementById('act').textContent = 'enter x and z'; return; }
          act('/api/goto', y ? x + ',' + y + ',' + z : x + ',' + z);
        }
        function slotBtn(s, i){
          const b = document.createElement('button');
          b.textContent = i + ': ' + (s || '-');
          if (!s) b.className = 'empty';
          b.onclick = e => post('/api/gui/click', i + ',0,' + (e.shiftKey ? 1 : 0));
          return b;
        }
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

            const g = await get('/api/menu');
            const gd = document.getElementById('gui'); gd.textContent = '';
            if (g.open) {
              gd.append(g.type + ' ');
              const cl = document.createElement('button'); cl.textContent = 'Close';
              cl.onclick = () => post('/api/gui/close', '0'); gd.append(cl);
              gd.append(' Holding: ' + (g.carried || 'nothing'));
              if (g.trades) g.trades.forEach((t, i) => {
                const b = document.createElement('button');
                b.textContent = [t.a, t.b, '=>', t.result].filter(Boolean).join(' ') + (t.out ? ' (sold out)' : '');
                b.onclick = () => post('/api/gui/trade', String(i));
                gd.append(document.createElement('br'), b);
              });
              if (g.enchantCosts) g.enchantCosts.forEach((cost, i) => {
                const b = document.createElement('button');
                b.textContent = 'Enchant option ' + (i + 1) + ' (level ' + cost + ')';
                b.onclick = () => post('/api/gui/enchant', String(i));
                gd.append(document.createElement('br'), b);
              });
              gd.append(document.createElement('br'), 'Container:', document.createElement('br'));
              g.slots.slice(0, g.invStart).forEach((s, i) => gd.append(slotBtn(s, i)));
              gd.append(document.createElement('br'), 'Your inventory (shift-click sends it in):', document.createElement('br'));
              g.slots.slice(g.invStart).forEach((s, j) => gd.append(slotBtn(s, g.invStart + j)));
            }
          } catch (e) {}
        }
        async function send(){
          const i = document.getElementById('msg'); const t = i.value.trim(); if (!t) return;
          const isCmd = t.startsWith('/');
          await post(isCmd ? '/api/command' : '/api/chat', isCmd ? t.slice(1) : t);
          i.value = '';
        }
        document.getElementById('msg').addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
        setInterval(poll, 1000); poll();
        </script>
        """;
}
