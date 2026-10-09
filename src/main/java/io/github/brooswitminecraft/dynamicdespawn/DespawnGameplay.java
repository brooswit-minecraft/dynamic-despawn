package io.github.brooswitminecraft.dynamicdespawn;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

/**
 * Core despawn behavior (MINECRAFT-147 / MINECRAFT-151): at a dropped-item
 * stack's natural despawn point, settle exactly one item into the world (or
 * bury it if there's nowhere valid to place it), decrement the stack by one,
 * and reset the remaining stack's timer. See README.md for the design
 * decisions this class implements (burial radius, tie-break, and the
 * "neither" fallback).
 */
@EventBusSubscriber(modid = DynamicDespawnMod.MODID)
public final class DespawnGameplay {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Matches vanilla's own default item despawn time ({@code ItemEntity.LIFETIME},
     * which is private). The remaining stack's despawn timer is reset to a full
     * span of this length. Items whose own lifespan differs from vanilla's
     * default (e.g. via {@code Item#getEntityLifespan}) are out of scope for
     * this slice's one-item-type behavior.
     */
    static final int RESET_LIFETIME_TICKS = 6000;

    /**
     * The "small fixed radius" MINECRAFT-145's decision comment calls for.
     * Burial searches a cube of this radius (Chebyshev distance) centered on
     * the despawning item, in blocks.
     */
    static final int BURIAL_SEARCH_RADIUS = 3;

    private DespawnGameplay() {}

    @SubscribeEvent
    public static void onItemExpire(ItemExpireEvent event) {
        ItemEntity itemEntity = event.getEntity();
        if (!(itemEntity.level() instanceof ServerLevel level)) {
            return;
        }

        ItemStack stack = itemEntity.getItem();
        int count = stack.getCount();
        if (count <= 0) {
            return;
        }

        BlockPos originPos = itemEntity.blockPosition();
        ItemStack single = stack.copyWithCount(1);

        boolean settled = tryGroundPlacement(level, originPos, single) || tryBurial(level, originPos, single);
        if (!settled) {
            // Neither placement nor burial has a valid destination: do nothing special and let
            // vanilla despawn proceed as normal (we leave event.extraLife at 0). PROVISIONAL
            // pending Brooswit's playtest, per MINECRAFT-145's decision comment.
            LOGGER.info(
                "[dynamicdespawn] no valid ground placement or burial destination for {} near {}; vanilla despawn proceeds",
                stack.getItem(), originPos);
            return;
        }

        if (count > 1) {
            stack.shrink(1);
            itemEntity.setItem(stack);
            event.addExtraLife(RESET_LIFETIME_TICKS);
        }
        // count == 1: extraLife stays 0, so this entity's own vanilla expiry still discards it
        // this tick -- "a stack of 1 leaves nothing" falls out of the existing vanilla behavior.
    }

    /**
     * Valid ground placement: the item's own block position is empty and the block below it has a
     * solid, sturdy top face. On success, places a single-item {@link ItemEntity} there with an
     * unlimited lifetime (it is "settled": a normal world item-on-ground object, pickable like any
     * other dropped item, that will not itself expire).
     */
    private static boolean tryGroundPlacement(ServerLevel level, BlockPos originPos, ItemStack single) {
        BlockPos belowPos = originPos.below();
        if (!level.getBlockState(originPos).isAir()) {
            return false;
        }
        if (!level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
            return false;
        }

        ItemEntity placed = new ItemEntity(
            level, originPos.getX() + 0.5, originPos.getY(), originPos.getZ() + 0.5, single);
        placed.setUnlimitedLifetime();
        level.addFreshEntity(placed);
        return true;
    }

    /**
     * Burial destination: the nearest eligible solid block within {@link #BURIAL_SEARCH_RADIUS},
     * chosen deterministically (ties broken by ascending scan order -- x, then y, then z -- within
     * the search volume; no randomness). On success, records the item against that block's position
     * in {@link BuriedItemsSavedData}; breaking that block later recovers it (see {@link
     * #onBlockBreak}).
     */
    private static boolean tryBurial(ServerLevel level, BlockPos originPos, ItemStack single) {
        BuriedItemsSavedData data = BuriedItemsSavedData.get(level);
        BlockPos destination = findBurialDestination(level, originPos, data);
        if (destination == null) {
            return false;
        }
        data.bury(destination, single);
        return true;
    }

    private static BlockPos findBurialDestination(ServerLevel level, BlockPos originPos, BuriedItemsSavedData data) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -BURIAL_SEARCH_RADIUS; dx <= BURIAL_SEARCH_RADIUS; dx++) {
            for (int dy = -BURIAL_SEARCH_RADIUS; dy <= BURIAL_SEARCH_RADIUS; dy++) {
                for (int dz = -BURIAL_SEARCH_RADIUS; dz <= BURIAL_SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    candidates.add(originPos.offset(dx, dy, dz));
                }
            }
        }
        // List.sort is stable, so candidates that tie on distance keep the scan order above.
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(originPos)));

        for (BlockPos pos : candidates) {
            if (data.isOccupied(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isFaceSturdy(level, pos, Direction.UP)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Burial recovery: breaking the host block drops the buried item back out. Only a direct break
     * of that exact block recovers it (see README.md's "Known limitations" section) -- an explosion,
     * piston, or other silent removal of the host block loses the buried item along with it.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BuriedItemsSavedData data = BuriedItemsSavedData.get(level);
        data.recover(event.getPos()).ifPresent(stack -> Block.popResource(level, event.getPos(), stack));
    }
}
