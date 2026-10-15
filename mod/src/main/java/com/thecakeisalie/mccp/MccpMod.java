package com.thecakeisalie.mccp;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod(MccpMod.MOD_ID)
public final class MccpMod {
    public static final String MOD_ID = "mccp";
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<EntityType<CompanionEntity>> COMPANION =
        ENTITY_TYPES.register("companion", () ->
            EntityType.Builder.<CompanionEntity>of(CompanionEntity::new, MobCategory.CREATURE)
                .sized(0.6F, 1.8F).clientTrackingRange(12).updateInterval(2)
                .build("mccp:companion"));

    public MccpMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(modBus);
        modBus.addListener(this::registerAttributes);
        MinecraftForge.EVENT_BUS.addListener(this::onTick);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(COMPANION.get(), CompanionEntity.createAttributes().build());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mccp")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("spawn").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CompanionEntity companion = Bridge.spawn(player.serverLevel(), player.position().add(2, 0, 2));
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "MCCP companion spawned: " + companion.getUUID()), true);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("remove").executes(ctx -> {
                int count = Bridge.removeCompanions(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Removed " + count + " MCCP companion(s)"), true);
                return count;
            }))
        );
    }

    private void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) Bridge.tick(server);
    }
}
