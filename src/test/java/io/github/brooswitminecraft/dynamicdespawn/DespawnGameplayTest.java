package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/**
 * Covers MINECRAFT-209's new BLOCK-item ground placement (and its fallback to burial) plus the
 * pre-existing decrement/timer-reset decision, which had no test coverage before this ticket
 * either. Scope note: this exercises {@code tryGroundPlacement}/{@code tryBurial} directly against
 * mocked {@code ServerLevel}/{@code BlockState}/{@code Block}/{@code Item} objects and a fake
 * {@link BurialStore}, never real vanilla registry instances ({@code Blocks.*}, {@code Items.*},
 * {@code EntityType.*}) or {@code Bootstrap.bootStrap()} -- in this repo's NeoForge moddev test
 * setup, touching any of those requires a live FML/mod-loading context that a bare JUnit process
 * doesn't have (confirmed empirically: {@code Bootstrap.bootStrap()} itself NPEs on {@code
 * net.neoforged.fml.loading.LoadingModList.get()} returning null, and skipping it instead throws
 * "Not bootstrapped" the moment a real {@code Blocks.*}/{@code Items.*} constant is touched).
 *
 * <p>Known gap from this constraint: there is no test here for the non-BLOCK settle path actually
 * constructing a real {@code ItemEntity} (its constructor touches the registry constant {@code
 * EntityType.ITEM}), so {@code trySettleItemEntity}'s world-entity-creation line itself is
 * exercised by the {@code addFreshEntity} interaction check below, not by constructing a real
 * entity. Full integration coverage of the real entity/world-registry path would need a NeoForge
 * GameTest (a real running server), which this repo has no infrastructure for.
 */
class DespawnGameplayTest {

    @Test
    void isBlockItem_blockItem_true() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getItem()).thenReturn(mock(BlockItem.class));
        assertTrue(DespawnGameplay.isBlockItem(stack));
    }

    @Test
    void isBlockItem_nonBlockItem_false() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getItem()).thenReturn(mock(Item.class));
        assertFalse(DespawnGameplay.isBlockItem(stack));
    }

    @Test
    void shouldResetRemainderTimer_stackGreaterThanOne_true() {
        assertTrue(DespawnGameplay.shouldResetRemainderTimer(8));
    }

    @Test
    void shouldResetRemainderTimer_stackOfOne_false() {
        assertFalse(DespawnGameplay.shouldResetRemainderTimer(1));
    }

    @Test
    void tryGroundPlacement_blockItem_validSpot_placesBlockInsteadOfSettlingItem() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        BlockPos below = origin.below();

        BlockState originState = mock(BlockState.class);
        when(originState.isAir()).thenReturn(true);
        BlockState belowState = mock(BlockState.class);
        when(belowState.isFaceSturdy(level, below, Direction.UP)).thenReturn(true);
        when(level.getBlockState(origin)).thenReturn(originState);
        when(level.getBlockState(below)).thenReturn(belowState);

        BlockState placeState = mock(BlockState.class);
        when(placeState.canSurvive(level, origin)).thenReturn(true);
        Block block = mock(Block.class);
        when(block.defaultBlockState()).thenReturn(placeState);
        BlockItem blockItem = mock(BlockItem.class);
        when(blockItem.getBlock()).thenReturn(block);
        ItemStack single = mock(ItemStack.class);
        when(single.getItem()).thenReturn(blockItem);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertTrue(result);
        verify(level).setBlock(eq(origin), eq(placeState), anyInt());
        verify(level, never()).addFreshEntity(any());
    }

    @Test
    void tryGroundPlacement_blockItem_invalidSpot_fallsThroughWithoutPlacing() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        // Origin position is already occupied (not air) -> not a valid ground spot.
        BlockState occupiedState = mock(BlockState.class);
        when(occupiedState.isAir()).thenReturn(false);
        when(level.getBlockState(origin)).thenReturn(occupiedState);

        ItemStack single = mock(ItemStack.class);
        when(single.getItem()).thenReturn(mock(BlockItem.class));

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertFalse(result);
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void tryGroundPlacement_blockItem_cannotSurvive_fallsThroughWithoutPlacing() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        BlockPos below = origin.below();

        BlockState originState = mock(BlockState.class);
        when(originState.isAir()).thenReturn(true);
        BlockState belowState = mock(BlockState.class);
        when(belowState.isFaceSturdy(level, below, Direction.UP)).thenReturn(true);
        when(level.getBlockState(origin)).thenReturn(originState);
        when(level.getBlockState(below)).thenReturn(belowState);

        BlockState placeState = mock(BlockState.class);
        when(placeState.canSurvive(level, origin)).thenReturn(false);
        Block block = mock(Block.class);
        when(block.defaultBlockState()).thenReturn(placeState);
        BlockItem blockItem = mock(BlockItem.class);
        when(blockItem.getBlock()).thenReturn(block);
        ItemStack single = mock(ItemStack.class);
        when(single.getItem()).thenReturn(blockItem);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertFalse(result);
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void tryGroundPlacement_nonBlockItem_validSpot_stillSettlesAsAnEntityNotABlock() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        BlockPos below = origin.below();

        BlockState originState = mock(BlockState.class);
        when(originState.isAir()).thenReturn(true);
        BlockState belowState = mock(BlockState.class);
        when(belowState.isFaceSturdy(level, below, Direction.UP)).thenReturn(true);
        when(level.getBlockState(origin)).thenReturn(originState);
        when(level.getBlockState(below)).thenReturn(belowState);

        ItemStack single = mock(ItemStack.class);
        when(single.getItem()).thenReturn(mock(Item.class));
        when(single.copyWithCount(anyInt())).thenReturn(single);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertTrue(result);
        verify(level, never()).setBlock(any(), any(), anyInt());
        verify(level).addFreshEntity(any());
    }

    @Test
    void tryBurial_blockItemThatCannotBePlaced_isBuriedInNearestEligibleBlock() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos onlyEligible = origin.offset(-1, 0, 0);

        BlockState nonSturdy = mock(BlockState.class);
        when(nonSturdy.isFaceSturdy(eq(level), any(), eq(Direction.UP))).thenReturn(false);
        BlockState sturdy = mock(BlockState.class);
        when(sturdy.isFaceSturdy(level, onlyEligible, Direction.UP)).thenReturn(true);

        when(level.isLoaded(any())).thenReturn(true);
        // Every candidate is non-sturdy by default except the one we want chosen.
        when(level.getBlockState(any())).thenReturn(nonSturdy);
        when(level.getBlockState(onlyEligible)).thenReturn(sturdy);

        FakeBurialStore store = new FakeBurialStore();
        ItemStack single = mock(ItemStack.class);

        boolean result = DespawnGameplay.tryBurial(level, origin, single, store);

        assertTrue(result);
        assertTrue(store.isOccupied(onlyEligible));
    }

    @Test
    void tryBurial_noEligibleDestination_returnsFalse() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(0, 64, 0);

        BlockState nonSturdy = mock(BlockState.class);
        when(nonSturdy.isFaceSturdy(eq(level), any(), eq(Direction.UP))).thenReturn(false);
        when(level.isLoaded(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(nonSturdy);

        FakeBurialStore store = new FakeBurialStore();
        ItemStack single = mock(ItemStack.class);

        boolean result = DespawnGameplay.tryBurial(level, origin, single, store);

        assertFalse(result);
        assertTrue(store.isEmpty());
    }

    /** In-memory {@link BurialStore} fake, standing in for {@link BuriedItemsSavedData} in tests. */
    private static final class FakeBurialStore implements BurialStore {
        private final Map<BlockPos, ItemStack> buried = new HashMap<>();

        @Override
        public boolean isOccupied(BlockPos pos) {
            return buried.containsKey(pos.immutable());
        }

        @Override
        public void bury(BlockPos pos, ItemStack stack) {
            buried.put(pos.immutable(), stack);
        }

        boolean isEmpty() {
            return buried.isEmpty();
        }
    }
}
