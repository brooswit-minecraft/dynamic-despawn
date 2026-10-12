package io.github.brooswitminecraft.dynamicdespawn;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

/** Implements the one-item-at-a-time authoritative despawn behavior. */
@EventBusSubscriber(modid = DynamicDespawnMod.MODID)
public final class DespawnGameplay {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final int RESET_LIFETIME_TICKS = 6000;
    static final int BARREL_SEARCH_RADIUS = 16;

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
        List<BlockPos> candidates = placementCandidates(originPos);
        SettleResult result = isBlockItem(single)
            ? resolvePlacement(count, placementAttempts(level, candidates, (BlockItem) single.getItem()))
            : resolveNonBlock(count, () -> tryStoreInNearbyBarrel(level, originPos, single),
                barrelCreationAttempts(level, candidates, single));

        // Every authoritative path consumes exactly one. For a stack of one, vanilla's expiry
        // consumes the entity this tick; for a larger stack we retain and reset only the remainder.
        if (result.resetRemainderTimer()) {
            stack.shrink(1);
            itemEntity.setItem(stack);
            event.addExtraLife(RESET_LIFETIME_TICKS);
        }
        LOGGER.debug("[dynamicdespawn] {} one {} at {}", result.outcome(), stack.getItem(), originPos);
    }

    enum SettleOutcome {
        PLACED,
        STORED,
        DESPAWNED
    }

    record SettleResult(SettleOutcome outcome, boolean resetRemainderTimer) {}

    /** A Minecraft-free representation of the required candidate sequence. */
    enum PlacementOffset {
        CURRENT(0, 0, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0),
        ABOVE(0, 1, 0);

        final int x;
        final int y;
        final int z;

        PlacementOffset(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Builds the required search order. Callers supply the randomized horizontal permutation so
     * this contract can be tested without a Minecraft runtime.
     */
    static List<PlacementOffset> placementOrder(List<PlacementOffset> horizontalOrder) {
        if (horizontalOrder.size() != 4
            || !horizontalOrder.containsAll(List.of(PlacementOffset.NORTH, PlacementOffset.SOUTH,
                PlacementOffset.WEST, PlacementOffset.EAST))) {
            throw new IllegalArgumentException("horizontal order must contain each horizontal neighbour once");
        }
        List<PlacementOffset> order = new ArrayList<>(6);
        order.add(PlacementOffset.CURRENT);
        order.addAll(horizontalOrder);
        order.add(PlacementOffset.ABOVE);
        return List.copyOf(order);
    }

    /** Returns PLACED at the first successful candidate; all failures consume one item. */
    static SettleResult resolvePlacement(int countBeforeSettling, List<BooleanSupplier> attempts) {
        for (BooleanSupplier attempt : attempts) {
            if (attempt.getAsBoolean()) {
                return result(SettleOutcome.PLACED, countBeforeSettling);
            }
        }
        return result(SettleOutcome.DESPAWNED, countBeforeSettling);
    }

    /** Stores in an existing barrel first, then tries new-barrel positions, then deletes one. */
    static SettleResult resolveNonBlock(int countBeforeSettling, BooleanSupplier tryExistingBarrel,
            List<BooleanSupplier> newBarrelAttempts) {
        if (tryExistingBarrel.getAsBoolean()) {
            return result(SettleOutcome.STORED, countBeforeSettling);
        }
        for (BooleanSupplier attempt : newBarrelAttempts) {
            if (attempt.getAsBoolean()) {
                return result(SettleOutcome.STORED, countBeforeSettling);
            }
        }
        return result(SettleOutcome.DESPAWNED, countBeforeSettling);
    }

    private static SettleResult result(SettleOutcome outcome, int countBeforeSettling) {
        return new SettleResult(outcome, shouldResetRemainderTimer(countBeforeSettling));
    }

    static boolean shouldResetRemainderTimer(int countBeforeSettling) {
        return countBeforeSettling > 1;
    }

    static boolean isBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    private static List<BlockPos> placementCandidates(BlockPos origin) {
        List<PlacementOffset> horizontal = new ArrayList<>(List.of(
            PlacementOffset.NORTH, PlacementOffset.SOUTH, PlacementOffset.WEST, PlacementOffset.EAST));
        Collections.shuffle(horizontal);
        return placementOrder(horizontal).stream()
            .map(offset -> origin.offset(offset.x, offset.y, offset.z))
            .toList();
    }

    private static List<BooleanSupplier> placementAttempts(ServerLevel level, List<BlockPos> candidates,
            BlockItem blockItem) {
        return candidates.stream().<BooleanSupplier>map(pos -> () -> tryPlaceBlock(level, pos, blockItem)).toList();
    }

    private static List<BooleanSupplier> barrelCreationAttempts(ServerLevel level, List<BlockPos> candidates,
            ItemStack single) {
        return candidates.stream().<BooleanSupplier>map(pos -> () -> tryCreateBarrel(level, pos, single)).toList();
    }

    private static boolean tryPlaceBlock(ServerLevel level, BlockPos pos, BlockItem blockItem) {
        if (!isValidGroundSpot(level, pos)) {
            return false;
        }
        BlockState placeState = blockItem.getBlock().defaultBlockState();
        return placeState.canSurvive(level, pos) && level.setBlock(pos, placeState, Block.UPDATE_ALL);
    }

    private static boolean tryStoreInNearbyBarrel(ServerLevel level, BlockPos origin, ItemStack single) {
        for (int dx = -BARREL_SEARCH_RADIUS; dx <= BARREL_SEARCH_RADIUS; dx++) {
            for (int dy = -BARREL_SEARCH_RADIUS; dy <= BARREL_SEARCH_RADIUS; dy++) {
                for (int dz = -BARREL_SEARCH_RADIUS; dz <= BARREL_SEARCH_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > BARREL_SEARCH_RADIUS * BARREL_SEARCH_RADIUS) {
                        continue;
                    }
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof BarrelBlockEntity barrel && insertOne(barrel, single)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean tryCreateBarrel(ServerLevel level, BlockPos pos, ItemStack single) {
        if (!isValidGroundSpot(level, pos)) {
            return false;
        }
        BlockState barrelState = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        if (!barrelState.canSurvive(level, pos) || !level.setBlock(pos, barrelState, Block.UPDATE_ALL)) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof BarrelBlockEntity barrel && insertOne(barrel, single)) {
            return true;
        }
        // A newly-created barrel without a usable inventory is not a valid fallback destination.
        level.removeBlock(pos, false);
        return false;
    }

    private static boolean insertOne(BarrelBlockEntity barrel, ItemStack single) {
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            ItemStack existing = barrel.getItem(slot);
            if (existing.isEmpty()) {
                barrel.setItem(slot, single.copy());
                barrel.setChanged();
                return true;
            }
            int limit = Math.min(existing.getMaxStackSize(), barrel.getMaxStackSize());
            if (ItemStack.isSameItemSameComponents(existing, single) && existing.getCount() < limit) {
                existing.grow(1);
                barrel.setChanged();
                return true;
            }
        }
        return false;
    }

    private static boolean isValidGroundSpot(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(pos).isAir()
            && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** Legacy buried data is only recovered when a prior version stored it; new despawns never bury. */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BuriedItemsSavedData data = BuriedItemsSavedData.get(level);
        data.recover(event.getPos()).ifPresent(stack -> Block.popResource(level, event.getPos(), stack));
    }
}
