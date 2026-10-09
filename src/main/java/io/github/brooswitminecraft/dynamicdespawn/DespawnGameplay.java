package io.github.brooswitminecraft.dynamicdespawn;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
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

        SettleResult result = settle(
            count, () -> tryGroundPlacement(level, originPos, single), () -> tryBurial(level, originPos, single));

        if (result.outcome() == SettleOutcome.NONE) {
            // Neither placement nor burial has a valid destination: do nothing special and let
            // vanilla despawn proceed as normal (we leave event.extraLife at 0). PROVISIONAL
            // pending Brooswit's playtest, per MINECRAFT-145's decision comment.
            LOGGER.info(
                "[dynamicdespawn] no valid ground placement or burial destination for {} near {}; vanilla despawn proceeds",
                stack.getItem(), originPos);
            return;
        }

        if (result.resetRemainderTimer()) {
            stack.shrink(1);
            itemEntity.setItem(stack);
            event.addExtraLife(RESET_LIFETIME_TICKS);
        }
        // count == 1: extraLife stays 0, so this entity's own vanilla expiry still discards it
        // this tick -- "a stack of 1 leaves nothing" falls out of the existing vanilla behavior.
    }

    /** Outcome of attempting to settle a single despawning item into the world. */
    enum SettleOutcome {
        PLACED,
        BURIED,
        NONE
    }

    /** The outcome of {@link #settle}, plus whether the remainder's timer should be reset. */
    record SettleResult(SettleOutcome outcome, boolean resetRemainderTimer) {}

    /**
     * Orchestration seam for MINECRAFT-209's review (AC4): ground-before-burial ordering, the
     * "neither" fallback, and the remainder-timer decision, expressed with no Minecraft types so
     * it is directly unit-testable. {@code tryBury} is only ever invoked when {@code tryGround}
     * returns {@code false} -- short-circuit {@code ||} semantics, not just "both happen to run
     * in order".
     */
    static SettleResult settle(int countBeforeSettling, BooleanSupplier tryGround, BooleanSupplier tryBury) {
        SettleOutcome outcome;
        if (tryGround.getAsBoolean()) {
            outcome = SettleOutcome.PLACED;
        } else if (tryBury.getAsBoolean()) {
            outcome = SettleOutcome.BURIED;
        } else {
            outcome = SettleOutcome.NONE;
        }
        boolean resetRemainderTimer = outcome != SettleOutcome.NONE && shouldResetRemainderTimer(countBeforeSettling);
        return new SettleResult(outcome, resetRemainderTimer);
    }

    /**
     * Whether settling/burying one item leaves a remainder that needs its timer reset. Pure
     * function of the pre-settlement count -- a stack of 1 has nothing left over.
     */
    static boolean shouldResetRemainderTimer(int countBeforeSettling) {
        return countBeforeSettling > 1;
    }

    /**
     * {@code true} iff this stack's item is a "BLOCK item" -- one that places a world block, per
     * MINECRAFT-209's membership rule: the stack's {@link net.minecraft.world.item.Item} is a
     * {@link BlockItem}. This mirrors vanilla's own notion of "an item you can place as a block"
     * (the same type right-click-to-place uses) rather than inventing a separate allow-list.
     */
    static boolean isBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    /**
     * Ground placement: for a BLOCK item, place the block it corresponds to; for any other item,
     * settle it as a loose item-on-ground. Both share the same ground-validity gate (the despawning
     * item's own position is empty and the block below has a solid, sturdy top face).
     */
    private static boolean tryGroundPlacement(ServerLevel level, BlockPos originPos, ItemStack single) {
        if (isBlockItem(single)) {
            return tryPlaceBlock(level, originPos, (BlockItem) single.getItem());
        }
        return trySettleItemEntity(level, originPos, single);
    }

    /**
     * BLOCK item ground placement: places the item's corresponding block at its default state on a
     * valid ground spot, consuming the item in the process (handled by the caller's decrement).
     * Simplified subset of the block's own placement rules -- this uses {@code Block#defaultBlockState}
     * plus the same ground-sturdy check as the non-block path, then the resulting state's own
     * {@code BlockState#canSurvive}, deliberately NOT running the block's full {@code
     * BlockPlaceContext}/{@code BlockItem#useOn} pipeline (no player, no facing/orientation, no
     * waterlogging) -- see README.md's design decisions for why. A block whose placement genuinely
     * depends on that fuller pipeline (e.g. stairs' facing) places in its default orientation here
     * rather than being excluded.
     */
    private static boolean tryPlaceBlock(ServerLevel level, BlockPos originPos, BlockItem blockItem) {
        if (!isValidGroundSpot(level, originPos)) {
            return false;
        }
        BlockState placeState = blockItem.getBlock().defaultBlockState();
        if (!placeState.canSurvive(level, originPos)) {
            return false;
        }
        level.setBlock(originPos, placeState, Block.UPDATE_ALL);
        return true;
    }

    /**
     * Non-BLOCK-item ground placement (unchanged from MINECRAFT-151): places a single-item {@link
     * ItemEntity} there with an unlimited lifetime (it is "settled": a normal world item-on-ground
     * object, pickable like any other dropped item, that will not itself expire).
     */
    private static boolean trySettleItemEntity(ServerLevel level, BlockPos originPos, ItemStack single) {
        if (!isValidGroundSpot(level, originPos)) {
            return false;
        }

        // The 7-arg constructor (rather than the 4-arg one) is used deliberately: the 4-arg
        // constructor gives the item a random horizontal velocity plus an upward kick, which made a
        // "settled" item visibly hop and drift instead of staying put (review finding on PR #2).
        ItemEntity placed = new ItemEntity(
            level, originPos.getX() + 0.5, originPos.getY(), originPos.getZ() + 0.5, single, 0.0, 0.0, 0.0);
        placed.setUnlimitedLifetime();
        level.addFreshEntity(placed);
        return true;
    }

    private static boolean isValidGroundSpot(ServerLevel level, BlockPos originPos) {
        BlockPos belowPos = originPos.below();
        if (!level.getBlockState(originPos).isAir()) {
            return false;
        }
        return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP);
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
            // isLoaded only checks whether the chunk (and the Y level) is already loaded; it never
            // forces a load. Skipping unloaded candidates here keeps the scan from force-loading
            // neighbouring chunks (review finding on PR #2 -- a chunk-safety AC).
            if (!level.isLoaded(pos)) {
                continue;
            }
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
