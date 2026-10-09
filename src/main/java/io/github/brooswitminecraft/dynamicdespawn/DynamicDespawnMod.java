package io.github.brooswitminecraft.dynamicdespawn;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Dynamic Despawn. Despawn/placement/burial gameplay is wired
 * up by {@link DespawnGameplay}, which {@code @EventBusSubscriber} registers
 * itself on the game event bus; this class only needs to exist for NeoForge
 * to load the mod.
 */
@Mod(DynamicDespawnMod.MODID)
public class DynamicDespawnMod {
    public static final String MODID = "dynamicdespawn";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicDespawnMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Dynamic Despawn loaded");
    }
}
