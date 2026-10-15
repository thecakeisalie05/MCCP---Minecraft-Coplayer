package com.thecakeisalie.mccp.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.thecakeisalie.mccp.Bridge;
import com.thecakeisalie.mccp.CompanionEntity;
import com.thecakeisalie.mccp.MccpMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional, CLIENT-ONLY OpenGL 3.3 voxel shader and offscreen framebuffer.
 * It does NOT render Minecraft's actual view: it uses a locally sampled RGB
 * occupancy volume. All GL calls occur on Minecraft's level-render thread.
 * PNG compression and localhost upload happen off that render thread.
 */
@Mod.EventBusSubscriber(modid = MccpMod.MOD_ID, value = Dist.CLIENT)
public final class GpuPreview {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int SX = 32;
    private static final int SY = 24;
    private static final int SZ = 32;
    private static final AtomicBoolean CONFIG_FETCHING = new AtomicBoolean(false);
    private static final AtomicBoolean POSTING = new AtomicBoolean(false);
    private record Config(String backend, int width, int height, int fps) {}
    private static volatile Config config = new Config("cpu", 64, 36, 2);
    private static long lastConfigCheck;
    private static long lastFrame;
    private static long lastVolume;
    private static long failedUntil;
    private static int program, vao, volumeTexture, framebuffer, frameTexture;
    private static int allocatedWidth, allocatedHeight;
    private static BlockPos volumeOrigin = BlockPos.ZERO;
    private GpuPreview() {}

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        long now = System.nanoTime();
        if (now - lastConfigCheck > 3_000_000_000L) {
            lastConfigCheck = now;
            refreshConfig();
        }
        Config settings = config;
        if (!"gpu".equals(settings.backend()) || now < failedUntil) return;
        if (now - lastFrame < 1_000_000_000L / settings.fps()) return;
        if (POSTING.get()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) return;
        CompanionEntity avatar = level.getEntitiesOfClass(
            CompanionEntity.class, minecraft.player.getBoundingBox().inflate(128)
        ).stream().findFirst().orElse(null);
        if (avatar == null) return;
        lastFrame = now;
        try {
            ensureCreated(settings.width(), settings.height());
            BlockPos desiredOrigin = BlockPos.containing(avatar.getEyePosition()).offset(-SX/2,-SY/2,-SZ/2);
            if (!desiredOrigin.equals(volumeOrigin) || now - lastVolume > 1_500_000_000L) {
                fillVoxelTexture(level, desiredOrigin);
                lastVolume = now;
                volumeOrigin = desiredOrigin;
            }
            byte[] pixels = render(settings, avatar);
            if (!POSTING.compareAndSet(false, true)) return;
            String id = avatar.getUUID().toString();
            int width = settings.width();
            int height = settings.height();
            CompletableFuture.runAsync(() -> encodeAndSend(pixels, width, height, id));
        } catch (Exception | LinkageError problem) {
            // GPU rendering must never crash the Minecraft render thread.
            failedUntil = now + 15_000_000_000L;
            LOG.warn("MCCP GPU shader preview failed; choose CPU mode or retry in 15s: {}",
                problem.toString(), problem);
        }
    }

    private static void refreshConfig() {
        if (!CONFIG_FETCHING.compareAndSet(false, true)) return;
        Bridge.getLocal("/api/v1/game/preview/config").whenComplete((raw, failure) -> {
            CONFIG_FETCHING.set(false);
            if (failure != null) return;
            try {
                JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                String backend = obj.get("backend").getAsString();
                int width = obj.get("width").getAsInt();
                int height = obj.get("height").getAsInt();
                int fps = obj.get("fps").getAsInt();
                if (!(backend.equals("cpu") || backend.equals("gpu"))
                        || width < 48 || width > 128 || width * 9 / 16 != height
                        || fps < 1 || fps > 8) return;
                config = new Config(backend, width, height, fps);
            } catch (RuntimeException exc) {
                LOG.warn("MCCP GPU preview settings invalid: {}", exc.toString());
            }
        });
    }

    private static String readShader(String path) throws IOException {
        try (InputStream stream = GpuPreview.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing shader resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compileShader(int kind, String contents) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, contents);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("GLSL compile failed: " + error);
        }
        return shader;
    }

    private static void ensureCreated(int width, int height) throws IOException {
        if (program == 0) {
            int vert = compileShader(GL20.GL_VERTEX_SHADER,
                readShader("/assets/mccp/shaders/voxel.vert"));
            int frag = compileShader(GL20.GL_FRAGMENT_SHADER,
                readShader("/assets/mccp/shaders/voxel.frag"));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vert);
            GL20.glAttachShader(program, frag);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("GLSL link failed: " + GL20.glGetProgramInfoLog(program));
            vao = GL30.glGenVertexArrays();
            volumeTexture = GL11.glGenTextures();
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int old = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volumeTexture);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SX, SY, SZ, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, old);
            GL13.glActiveTexture(active);
            framebuffer = GL30.glGenFramebuffers();
            frameTexture = GL11.glGenTextures();
        }
        if (width != allocatedWidth || height != allocatedHeight) {
            int old2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, frameTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, old2d);
            int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, frameTexture, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("GPU preview framebuffer incomplete: " + status);
            allocatedWidth = width;
            allocatedHeight = height;
        }
    }

    private static void fillVoxelTexture(ClientLevel level, BlockPos base) {
        ByteBuffer cells = BufferUtils.createByteBuffer(SX * SY * SZ * 4);
        for (int z = 0; z < SZ; z++) {
            for (int y = 0; y < SY; y++) {
                for (int x = 0; x < SX; x++) {
                    BlockPos pos = base.offset(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        cells.putInt(0);
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        cells.putInt(0);
                        continue;
                    }
                    int rgb = state.getMapColor(level, pos)
                        .calculateRGBColor(MapColor.Brightness.NORMAL);
                    cells.put((byte) ((rgb >> 16) & 255));
                    cells.put((byte) ((rgb >> 8) & 255));
                    cells.put((byte) (rgb & 255));
                    cells.put((byte) 255);
                }
            }
        }
        cells.flip();
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, volumeTexture);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0,
            SX, SY, SZ, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cells);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        GL13.glActiveTexture(active);
    }

    private static byte[] render(Config settings, CompanionEntity avatar) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int[] vp = new int[] {viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3)};
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int old3d = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, settings.width(), settings.height());
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volumeTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "u_blocks"), 0);
            Vec3 forward = avatar.getLookAngle().normalize();
            Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
            if (right.lengthSqr() < 0.001) right = new Vec3(1, 0, 0);
            Vec3 up = right.cross(forward).normalize();
            Vec3 eye = avatar.getEyePosition().subtract(Vec3.atLowerCornerOf(volumeOrigin));
            uniformVec("u_forward", forward);
            uniformVec("u_right", right);
            uniformVec("u_up", up);
            uniformVec("u_eye", eye);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "u_size"), SX, SY, SZ);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_aspect"),
                (float) settings.width() / settings.height());
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_tanFov"),
                (float) Math.tan(Math.toRadians(70) / 2));
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            ByteBuffer rgba = BufferUtils.createByteBuffer(settings.width() * settings.height() * 4);
            GL11.glReadPixels(0, 0, settings.width(), settings.height(),
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
            byte[] result = new byte[rgba.remaining()];
            rgba.get(result);
            return result;
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, old3d);
            GL13.glActiveTexture(previousActiveTex);
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVao);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        }
    }

    private static void uniformVec(String name, Vec3 value) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, name),
            (float) value.x, (float) value.y, (float) value.z);
    }

    private static void encodeAndSend(byte[] pixels, int width, int height, String id) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = ((height - 1 - y) * width + x) * 4;
                    int rgb = ((pixels[offset] & 255) << 16)
                        | ((pixels[offset + 1] & 255) << 8)
                        | (pixels[offset + 2] & 255);
                    image.setRGB(x, y, rgb);
                }
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", encoded)) return;
            JsonObject report = new JsonObject();
            report.addProperty("protocol", "mccp-frame-v1");
            report.addProperty("companion_id", id);
            report.addProperty("frame_kind", "gpu_voxel_shader_png");
            report.addProperty("frame_width", width);
            report.addProperty("frame_height", height);
            report.addProperty("frame_png_base64",
                Base64.getEncoder().encodeToString(encoded.toByteArray()));
            Bridge.postLocal("/api/v1/game/frame", report.toString())
                .whenComplete((unused, error) -> {
                    if (error != null) LOG.debug("GPU frame transport: {}", error.toString());
                    POSTING.set(false);
                });
        } catch (Exception error) {
            LOG.warn("MCCP GPU PNG encoding failed", error);
            POSTING.set(false);
        }
    }
}
