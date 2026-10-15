package com.thecakeisalie.mccp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Bridge {
    private static final Logger LOG = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(900)).build();
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);
    private static final AtomicBoolean SENDING = new AtomicBoolean(false);
    private static long ticks;
    private static int failures;

    private Bridge() {}

    public static CompanionEntity spawn(ServerLevel world, Vec3 position) {
        for (CompanionEntity previous : world.getEntitiesOfClass(CompanionEntity.class,
                new net.minecraft.world.phys.AABB(position, position).inflate(256))) {
            return previous; // one companion per local active area in this alpha
        }
        CompanionEntity companion = MccpMod.COMPANION.get().create(world);
        if (companion == null) throw new IllegalStateException("Companion type not registered");
        companion.moveTo(position.x, position.y, position.z, 0, 0);
        companion.setCustomName(Component.literal("MCCP"));
        companion.setCustomNameVisible(true);
        companion.setPersistenceRequired();
        world.addFreshEntity(companion);
        return companion;
    }

    public static int removeCompanions(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (CompanionEntity companion : level.getEntitiesOfClass(
                    CompanionEntity.class, new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000, 30000000, 2048, 30000000))) {
                companion.discard();
                removed++;
            }
        }
        return removed;
    }

    private static CompanionEntity findCompanion(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (CompanionEntity companion : player.serverLevel().getEntitiesOfClass(
                    CompanionEntity.class, player.getBoundingBox().inflate(128))) {
                return companion;
            }
        }
        return null;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % 5 == 0) poll(server); // 4Hz command polling while world active
        if (ticks % 40 == 0) { // 0.5Hz heavy perception: bounded raycast, nearby blocks
            CompanionEntity companion = findCompanion(server);
            if (companion != null) sendTelemetry(Observer.capture(companion, true));
        }
    }

    private static String token() {
        String configured = System.getProperty("mccp.token");
        if (configured != null && !configured.isBlank()) return configured.trim();
        String dir = System.getenv("MCCP_HOME");
        if (dir == null || dir.isBlank()) {
            String appdata = System.getenv("APPDATA");
            dir = appdata != null ? appdata + "\\MCCP"
                : System.getProperty("user.home") + "/.config/mccp";
        }
        try {
            return Files.readString(Path.of(dir, "api-token"), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String apiBase() {
        String configured = System.getProperty("mccp.api", "http://127.0.0.1:8765");
        URI uri = URI.create(configured);
        if (!"http".equals(uri.getScheme()) ||
                !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))) {
            throw new IllegalStateException("MCCP only permits localhost API in this alpha");
        }
        return configured.replaceAll("/+$", "");
    }

    private static CompletableFuture<String> post(String path, String json) {
        String secret = token();
        if (secret == null || secret.isBlank()) return CompletableFuture.failedFuture(
            new IOException("MCCP token file is missing; run MCCP.exe first"));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiBase() + path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .header("X-MCCP-Token", secret)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("MCCP API returned " + response.statusCode());
                }
                return response.body();
            });
    }

    private static void warn(Throwable exception) {
        if (++failures % 100 == 1) {
            LOG.warn("MCCP bridge unavailable: {}", exception.toString());
        }
    }

    private static void poll(MinecraftServer server) {
        if (!POLLING.compareAndSet(false, true)) return;
        post("/api/v1/commands/poll", "{}").whenComplete((body, error) -> {
            POLLING.set(false);
            if (error != null) { warn(error); return; }
            failures = 0;
            try {
                JsonObject response = JsonParser.parseString(body).getAsJsonObject();
                JsonArray commands = response.getAsJsonArray("commands");
                if (commands != null && !commands.isEmpty()) {
                    server.execute(() -> {
                        for (JsonElement element : commands) {
                            try { execute(server, element.getAsJsonObject()); }
                            catch (Exception ex) { LOG.warn("MCCP command rejected: {}", ex.toString()); }
                        }
                    });
                }
            } catch (RuntimeException ex) { warn(ex); }
        });
    }

    private static void sendTelemetry(JsonObject telemetry) {
        if (!SENDING.compareAndSet(false, true)) return;
        post("/api/v1/game/telemetry", telemetry.toString()).whenComplete((body, err) -> {
            SENDING.set(false);
            if (err != null) warn(err);
        });
    }

    private static void execute(MinecraftServer server, JsonObject command) {
        String kind = string(command, "kind", "");
        JsonObject args = command.has("arguments") && command.get("arguments").isJsonObject()
            ? command.getAsJsonObject("arguments") : new JsonObject();
        CompanionEntity companion = findCompanion(server);
        if ("spawn".equals(kind)) {
            if (companion != null) return;
            ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (player != null) spawn(player.serverLevel(), player.position().add(2, 0, 2));
            return;
        }
        if (companion == null) return;
        switch (kind) {
            case "despawn" -> companion.discard();
            case "stop" -> companion.getNavigation().stop();
            case "move_to" -> {
                double x = number(args, "x", companion.getX());
                double y = number(args, "y", companion.getY());
                double z = number(args, "z", companion.getZ());
                if (companion.position().distanceToSqr(x, y, z) > 64 * 64) return;
                companion.getNavigation().moveTo(x, y, z, 1.0);
            }
            case "look_at" -> {
                double x = number(args, "x", companion.getX());
                double y = number(args, "y", companion.getEyeY());
                double z = number(args, "z", companion.getZ());
                Vec3 delta = new Vec3(x - companion.getX(), y - companion.getEyeY(), z - companion.getZ());
                if (delta.lengthSqr() < 0.0001) return;
                float yaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                float pitch = (float) (-Math.toDegrees(
                    Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
                companion.setYRot(yaw);
                companion.setXRot(pitch);
                companion.setYHeadRot(yaw);
                companion.setYBodyRot(yaw);
            }
            case "follow" -> {
                String name = string(args, "player", "");
                ServerPlayer player = server.getPlayerList().getPlayers().stream()
                    .filter(p -> name.isBlank() || p.getGameProfile().getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
                if (player != null && player.level() == companion.level())
                    companion.getNavigation().moveTo(player, 1.0);
            }
            case "say" -> {
                String text = string(args, "text", "");
                if (text.length() > 240) text = text.substring(0, 240);
                if (!text.isBlank()) {
                    final String spoken = text;
                    server.getPlayerList().broadcastSystemMessage(
                        Component.literal("<MCCP> " + spoken), false);
                }
            }
            case "inspect" -> { /* Sensor telemetry continuously reports blocks and entities. */ }
            default -> LOG.warn("Unsupported MCCP action: {}", kind);
        }
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : fallback;
    }
    private static double number(JsonObject obj, String key, double fallback) {
        if (!obj.has(key)) return fallback;
        try {
            double result = obj.get(key).getAsDouble();
            return Double.isFinite(result) ? result : fallback;
        } catch (Exception exception) { return fallback; }
    }
}
