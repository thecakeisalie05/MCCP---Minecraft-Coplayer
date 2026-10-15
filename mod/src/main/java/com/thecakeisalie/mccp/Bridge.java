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
    private record PreviewConfig(String backend, int width, int height, int fps) {}
    private static volatile PreviewConfig preview = new PreviewConfig("cpu", 64, 36, 2);
    private static long lastCpuFrameTick;
    private static long throttleCpuUntil;

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
        // Never query an AABB spanning the entire dimension; that may force
        // enormous entity-section scans and stall the tick thread.
        int removed = 0;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            for (CompanionEntity companion : online.serverLevel().getEntitiesOfClass(
                    CompanionEntity.class, online.getBoundingBox().inflate(128))) {
                if (companion.isRemoved()) continue;
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
        if (ticks % 5 == 0) poll(server);
        if (ticks % 100 == 1) refreshPreview();
        // Pose/stats = 5Hz; heavy block scan = 0.5Hz; camera = configured FPS.
        // Never do network IO synchronously on the server tick thread.
        if (ticks % 4 != 0) return;
        CompanionEntity companion = findCompanion(server);
        if (companion == null) return;
        PreviewConfig settings = preview;
        boolean environment = ticks % 40 == 0;
        int framePeriod = Math.max(1, Math.round(20f / settings.fps()));
        boolean frame = settings.backend().equals("cpu")
            && ticks >= throttleCpuUntil && ticks - lastCpuFrameTick >= framePeriod;
        if (frame) lastCpuFrameTick = ticks;
        long start = System.nanoTime();
        try {
            sendTelemetry(Observer.capture(companion, environment,
                settings.width(), settings.height(), frame));
        } catch (RuntimeException problem) {
            LOG.warn("MCCP telemetry capture failed; retrying pose-only: {}", problem.toString(), problem);
            try {
                JsonObject basic = Observer.capture(companion, false,
                    settings.width(), settings.height(), false);
                basic.addProperty("frame_error", problem.getClass().getSimpleName());
                sendTelemetry(basic);
            } catch (RuntimeException basicFailure) {
                LOG.error("MCCP pose-only telemetry also failed", basicFailure);
            }
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1000000L;
            if (frame && elapsedMs >= 35L) {
                // Skip a few frames following an unexpectedly expensive capture,
                // rather than continuously degrading the Minecraft server TPS.
                throttleCpuUntil = ticks + 20;
                LOG.warn("MCCP CPU preview took {} ms. Temporarily throttling camera.", elapsedMs);
            }
        }
    }

    private static void refreshPreview() {
        getLocal("/api/v1/game/preview/config").thenAccept(raw -> {
            try {
                JsonObject cfg = JsonParser.parseString(raw).getAsJsonObject();
                String mode = cfg.get("backend").getAsString();
                int width = cfg.get("width").getAsInt();
                int height = cfg.get("height").getAsInt();
                int fps = cfg.get("fps").getAsInt();
                if (!(mode.equals("cpu") || mode.equals("gpu"))
                        || width < 48 || width > 128 || height != width * 9 / 16
                        || fps < 1 || fps > 8) return;
                preview = new PreviewConfig(mode, width, height, fps);
            } catch (RuntimeException ex) {
                LOG.warn("Invalid MCCP preview configuration from brain: {}", ex.toString());
            }
        }).exceptionally(err -> null); // Brain may be offline on startup.
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

    public static CompletableFuture<String> getLocal(String path) {
        String secret = token();
        if (secret == null || secret.isBlank())
            return CompletableFuture.failedFuture(new IOException("MCCP token unavailable"));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiBase() + path))
            .timeout(Duration.ofSeconds(3))
            .header("X-MCCP-Token", secret)
            .GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("MCCP HTTP " + response.statusCode());
                return response.body();
            });
    }

    public static CompletableFuture<String> postLocal(String path, String body) {
        return post(path, body);
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
            case "jump" -> companion.getJumpControl().jump();
            case "turn_by" -> {
                double degrees = Math.max(-90.0, Math.min(90.0, number(args, "degrees", 0)));
                float yaw = companion.getYRot() + (float) degrees;
                companion.setYRot(yaw);
                companion.setYHeadRot(yaw);
                companion.setYBodyRot(yaw);
            }
            case "move_relative" -> {
                double forward = Math.max(-1, Math.min(1, number(args, "forward", 0)));
                double strafe = Math.max(-1, Math.min(1, number(args, "strafe", 0)));
                double distance = Math.max(0, Math.min(3, number(args, "blocks", 1)));
                double yaw = Math.toRadians(companion.getYRot());
                Vec3 forwardVector = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Vec3 sideVector = new Vec3(forwardVector.z, 0, -forwardVector.x);
                Vec3 delta = forwardVector.scale(forward).add(sideVector.scale(strafe));
                if (delta.lengthSqr() > 0.0001) {
                    Vec3 target = companion.position().add(delta.normalize().scale(distance));
                    companion.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
                }
            }
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
