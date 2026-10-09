package io.github.brooswitminecraft.dynamicdespawn;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Dynamic Despawn (MINECRAFT-146). Registers the mod with no
 * gameplay logic yet; despawn/placement/burial behavior is a later story.
 */
@Mod(DynamicDespawnMod.MODID)
public class DynamicDespawnMod {
    public static final String MODID = "dynamicdespawn";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicDespawnMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Dynamic Despawn loaded");
    }
}
