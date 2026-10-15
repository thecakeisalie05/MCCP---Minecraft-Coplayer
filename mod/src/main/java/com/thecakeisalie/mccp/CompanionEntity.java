package com.thecakeisalie.mccp;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public final class CompanionEntity extends PathfinderMob {
    private final SimpleContainer backpack = new SimpleContainer(36);
    private int hunger = 20;
    private float saturation = 5.0F;

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20)
            .add(Attributes.MOVEMENT_SPEED, 0.30)
            .add(Attributes.FOLLOW_RANGE, 32);
    }

    public SimpleContainer backpack() { return backpack; }
    public int hunger() { return hunger; }
    public float saturation() { return saturation; }
    public void setHunger(int value) { hunger = Math.max(0, Math.min(20, value)); }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            this.setYHeadRot(this.getYRot());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("MCCPInventory", backpack.createTag());
        tag.putInt("MCCPHunger", hunger);
        tag.putFloat("MCCPSaturation", saturation);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("MCCPInventory")) backpack.fromTag(tag.getList("MCCPInventory", 10));
        if (tag.contains("MCCPHunger")) setHunger(tag.getInt("MCCPHunger"));
        if (tag.contains("MCCPSaturation")) saturation = tag.getFloat("MCCPSaturation");
    }

    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }
    @Override
    public boolean removeWhenFarAway(double distance) { return false; }
}
