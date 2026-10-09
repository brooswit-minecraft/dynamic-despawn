package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * Covers MINECRAFT-209's new BLOCK-item ground placement (and its fallback to burial) plus the
 * pre-existing decrement/timer-reset decision, which had no test coverage before this ticket
 * either. Scope note: this exercises {@code tryGroundPlacement}/{@code tryBurial} directly against
 * a mocked {@code ServerLevel} and a fake {@link BurialStore}, not the full {@code onItemExpire}
 * event flow -- the latter would additionally require a real NeoForge event bus and a real {@code
 * ServerLevel#getDataStorage()}, neither of which this repo has infrastructure for (there was no
 * test infrastructure at all before this ticket). The composed OR-fallback in {@code
 * onItemExpire} is exactly the two methods tested here, called one after the other.
 *
 * <p>No {@code Bootstrap.bootStrap()} call: referencing {@code Items}/{@code Blocks} constants
 * triggers their own static registration eagerly, which is all these tests need; a prior attempt
 * at calling {@code Bootstrap.bootStrap()} here threw an NPE in this environment (it validates
 * built-in registries against data this unit-test classpath doesn't carry), so it is deliberately
 * left out rather than worked around.
 */
class DespawnGameplayTest {

    @Test
    void isBlockItem_blockItem_true() {
        assertTrue(DespawnGameplay.isBlockItem(new ItemStack(Items.DIRT)));
    }

    @Test
    void isBlockItem_nonBlockItem_false() {
        assertFalse(DespawnGameplay.isBlockItem(new ItemStack(Items.DIAMOND)));
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
        when(level.getBlockState(origin)).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.getBlockState(below)).thenReturn(Blocks.STONE.defaultBlockState());

        ItemStack single = new ItemStack(Items.DIRT, 1);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertTrue(result);
        verify(level).setBlock(origin, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        verify(level, never()).addFreshEntity(any());
    }

    @Test
    void tryGroundPlacement_blockItem_invalidSpot_fallsThroughWithoutPlacing() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        // Origin position is already occupied (not air) -> not a valid ground spot.
        when(level.getBlockState(origin)).thenReturn(Blocks.STONE.defaultBlockState());

        ItemStack single = new ItemStack(Items.DIRT, 1);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertFalse(result);
        verify(level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void tryGroundPlacement_nonBlockItem_validSpot_stillSettlesItemEntity() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(5, 64, 5);
        BlockPos below = origin.below();
        when(level.getBlockState(origin)).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.getBlockState(below)).thenReturn(Blocks.STONE.defaultBlockState());

        ItemStack single = new ItemStack(Items.DIAMOND, 1);

        boolean result = DespawnGameplay.tryGroundPlacement(level, origin, single);

        assertTrue(result);
        verify(level, never()).setBlock(any(), any(), anyInt());
        verify(level).addFreshEntity(any(ItemEntity.class));
    }

    @Test
    void tryBurial_blockItemThatCannotBePlaced_isBuriedInNearestEligibleBlock() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos onlyEligible = origin.offset(-1, 0, 0);

        when(level.isLoaded(any())).thenReturn(true);
        // Every candidate is non-sturdy air by default except the one we want chosen.
        when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.getBlockState(onlyEligible)).thenReturn(Blocks.STONE.defaultBlockState());

        FakeBurialStore store = new FakeBurialStore();
        ItemStack single = new ItemStack(Items.DIRT, 1);

        boolean result = DespawnGameplay.tryBurial(level, origin, single, store);

        assertTrue(result);
        ItemStack buried = store.buriedAt(onlyEligible);
        assertEquals(Items.DIRT, buried.getItem());
        assertEquals(1, buried.getCount());
    }

    @Test
    void tryBurial_noEligibleDestination_returnsFalse() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos origin = new BlockPos(0, 64, 0);

        when(level.isLoaded(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());

        FakeBurialStore store = new FakeBurialStore();
        ItemStack single = new ItemStack(Items.DIRT, 1);

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
            buried.put(pos.immutable(), stack.copy());
        }

        ItemStack buriedAt(BlockPos pos) {
            return buried.get(pos.immutable());
        }

        boolean isEmpty() {
            return buried.isEmpty();
        }
    }
}
