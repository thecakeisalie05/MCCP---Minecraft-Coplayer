package com.thecakeisalie.mccp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Game-thread-only observer of the companion's real server-side world.
 * The PNG is a SEMANTIC RAYCAST, not a GPU framebuffer screenshot.
 */
public final class Observer {
    private static final int RADIUS = 12;
    private static final int MAX_BLOCKS = 1200;
    private static final int MAX_ENTITIES = 128;
    private static final int DEFAULT_WIDTH = 64;
    private static final int DEFAULT_HEIGHT = 36;
    private Observer() {}

    public static JsonObject capture(CompanionEntity avatar, boolean includeEnvironment) {
        return capture(avatar, includeEnvironment, DEFAULT_WIDTH, DEFAULT_HEIGHT, includeEnvironment);
    }

    public static JsonObject capture(CompanionEntity avatar, boolean includeEnvironment,
                                      int width, int height, boolean includeFrame) {
        width = Math.max(48, Math.min(128, width));
        height = Math.max(27, Math.min(72, height));
        ServerLevel level = (ServerLevel) avatar.level();
        JsonObject root = new JsonObject();
        root.addProperty("protocol", "mccp-game-v1");
        root.addProperty("timestamp_ms", System.currentTimeMillis());
        root.addProperty("companion_id", avatar.getUUID().toString());
        root.addProperty("dimension", level.dimension().location().toString());
        JsonObject pose = new JsonObject();
        pose.addProperty("x", avatar.getX());
        pose.addProperty("y", avatar.getY());
        pose.addProperty("z", avatar.getZ());
        pose.addProperty("eye_y", avatar.getEyeY());
        pose.addProperty("yaw", avatar.getYRot());
        pose.addProperty("pitch", avatar.getXRot());
        pose.addProperty("head_yaw", avatar.getYHeadRot());
        pose.addProperty("bearing_degrees", (avatar.getYRot() % 360 + 360) % 360);
        pose.addProperty("heading", avatar.getDirection().getName());
        pose.addProperty("vx", avatar.getDeltaMovement().x);
        pose.addProperty("vy", avatar.getDeltaMovement().y);
        pose.addProperty("vz", avatar.getDeltaMovement().z);
        Vec3 look = avatar.getLookAngle();
        JsonObject direction = new JsonObject();
        direction.addProperty("x", look.x);
        direction.addProperty("y", look.y);
        direction.addProperty("z", look.z);
        pose.add("look", direction);
        root.add("pose", pose);
        JsonObject stats = new JsonObject();
        stats.addProperty("health", avatar.getHealth());
        stats.addProperty("max_health", avatar.getMaxHealth());
        stats.addProperty("hunger", avatar.hunger());
        stats.addProperty("saturation", avatar.saturation());
        stats.addProperty("air", avatar.getAirSupply());
        stats.addProperty("on_ground", avatar.onGround());
        stats.addProperty("sprinting", avatar.isSprinting());
        stats.addProperty("swimming", avatar.isSwimming());
        stats.addProperty("navigation_done", avatar.getNavigation().isDone());
        root.add("stats", stats);
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < avatar.backpack().getContainerSize(); slot++) {
            ItemStack stack = avatar.backpack().getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            item.addProperty("slot", slot);
            item.addProperty("item", key.toString());
            item.addProperty("mod", key.getNamespace());
            item.addProperty("count", stack.getCount());
            item.addProperty("display_name", stack.getHoverName().getString());
            item.addProperty("durability", stack.getDamageValue());
            if (stack.hasTag()) item.addProperty("nbt", limit(stack.getTag().toString(), 350));
            inventory.add(item);
        }
        root.add("inventory", inventory);
        root.addProperty("frame_kind", includeFrame ? "semantic_raycast_png" : "unavailable");
        root.addProperty("frame_width", width);
        root.addProperty("frame_height", height);
        if (includeEnvironment) {
            root.add("entities", entities(level, avatar, look));
            root.add("blocks", blocks(level, avatar, look, root));
        }
        if (includeFrame) {
            try {
                root.addProperty("frame_png_base64", renderFrame(level, avatar, look, width, height));
                root.addProperty("frame_timestamp_ms", System.currentTimeMillis());
            } catch (IOException exception) {
                root.addProperty("frame_error", exception.getClass().getSimpleName());
            }
        }
        return root;
    }

    private static JsonArray entities(ServerLevel level, CompanionEntity avatar, Vec3 forward) {
        JsonArray array = new JsonArray();
        Vec3 eye = avatar.getEyePosition();
        for (Entity entity : level.getEntities(avatar, new AABB(
                eye.x - RADIUS, eye.y - RADIUS, eye.z - RADIUS,
                eye.x + RADIUS, eye.y + RADIUS, eye.z + RADIUS))) {
            Vec3 offset = entity.getEyePosition().subtract(eye);
            if (offset.lengthSqr() > RADIUS * RADIUS || offset.lengthSqr() < 0.0001) continue;
            if (offset.normalize().dot(forward) < 0.45) continue;
            JsonObject obj = new JsonObject();
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            obj.addProperty("uuid", entity.getUUID().toString());
            obj.addProperty("type", key.toString());
            obj.addProperty("mod", key.getNamespace());
            obj.addProperty("x", entity.getX());
            obj.addProperty("y", entity.getY());
            obj.addProperty("z", entity.getZ());
            obj.addProperty("yaw", entity.getYRot());
            obj.addProperty("pitch", entity.getXRot());
            obj.addProperty("name", entity.getDisplayName().getString());
            if (entity instanceof LivingEntity living) obj.addProperty("health", living.getHealth());
            array.add(obj);
            if (array.size() >= MAX_ENTITIES) break;
        }
        return array;
    }

    private static JsonArray blocks(ServerLevel level, CompanionEntity avatar, Vec3 forward, JsonObject root) {
        JsonArray array = new JsonArray();
        BlockPos origin = avatar.blockPosition();
        int scanned = 0;
        boolean truncated = false;
        search:
        for (int dy = -7; dy <= 7; dy++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (dx * dx + dz * dz + dy * dy > RADIUS * RADIUS) continue;
                    Vec3 delta = new Vec3(dx + 0.5, dy + 0.5, dz + 0.5);
                    if (delta.lengthSqr() < 1 || delta.normalize().dot(forward) < 0.60) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    scanned++;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    JsonObject block = new JsonObject();
                    block.addProperty("x", pos.getX());
                    block.addProperty("y", pos.getY());
                    block.addProperty("z", pos.getZ());
                    block.addProperty("block", key.toString());
                    block.addProperty("mod", key.getNamespace());
                    block.addProperty("data", limit(state.toString(), 240));
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        block.addProperty("block_entity_type", String.valueOf(
                            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
                        // No unrestricted inventory/capability NBT exfiltration.
                    }
                    array.add(block);
                    if (array.size() >= MAX_BLOCKS) {
                        truncated = true;
                        break search;
                    }
                }
            }
        }
        root.addProperty("block_samples_scanned", scanned);
        root.addProperty("blocks_truncated", truncated);
        root.addProperty("block_radius", RADIUS);
        root.addProperty("block_selection", "front_cone_not_occlusion_filtered");
        return array;
    }

    private static String renderFrame(ServerLevel level, CompanionEntity avatar, Vec3 forward,
                                      int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < 0.001) right = new Vec3(1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        double tanHalfVerticalFov = Math.tan(Math.toRadians(70) / 2);
        double aspect = (double) width / height;
        Vec3 eye = avatar.getEyePosition();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sx = (2.0 * (x + 0.5) / width - 1.0) * aspect * tanHalfVerticalFov;
                double sy = (1.0 - 2.0 * (y + 0.5) / height) * tanHalfVerticalFov;
                Vec3 ray = forward.add(right.scale(sx)).add(up.scale(sy)).normalize();
                BlockHitResult hit = level.clip(new ClipContext(
                    eye, eye.add(ray.scale(24)), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, avatar));
                int color = 0x293d52; // sky/empty
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos pos = hit.getBlockPos();
                    BlockState state = level.getBlockState(pos);
                    MapColor mapColor = state.getMapColor(level, pos);
                    color = mapColor.calculateRGBColor(MapColor.Brightness.NORMAL) & 0xFFFFFF;
                    double distance = Math.sqrt(pos.distSqr(BlockPos.containing(eye)));
                    double factor = Math.max(0.38, 1.0 - distance / 38.0);
                    int r = (int) (((color >> 16) & 255) * factor);
                    int g = (int) (((color >> 8) & 255) * factor);
                    int b = (int) ((color & 255) * factor);
                    color = (r << 16) | (g << 8) | b;
                }
                image.setRGB(x, y, color);
            }
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("No PNG encoder");
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    private static String limit(String value, int maximum) {
        return value.length() > maximum ? value.substring(0, maximum) : value;
    }
}
