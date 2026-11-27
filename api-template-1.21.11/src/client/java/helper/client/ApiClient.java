package helper.client;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.AbstractClientPlayer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ApiClient implements ClientModInitializer {
    private static volatile String snapshot = "{\"players\":[]}";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (client.level.getGameTime() % 10 != 0) return;

            StringBuilder sb = new StringBuilder("{\"players\":[");
            boolean first = true;
            for (AbstractClientPlayer p : client.level.players()) {
                if (p == client.player) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(String.format(java.util.Locale.ROOT,
                    "{\"name\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"distance\":%.1f}",
                    p.getName().getString(), p.getX(), p.getY(), p.getZ(),
                    p.distanceTo(client.player)));
            }
            snapshot = sb.append("]}").toString();
        });

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
            server.createContext("/players", ex -> {
                byte[] body = snapshot.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                ex.sendResponseHeaders(200, body.length);
                try (var os = ex.getResponseBody()) { os.write(body); }
            });
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}