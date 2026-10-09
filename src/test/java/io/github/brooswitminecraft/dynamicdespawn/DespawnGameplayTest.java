package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Covers MINECRAFT-209's BLOCK-item membership rule and the pre-existing decrement/timer-reset
 * decision -- the parts of {@code DespawnGameplay} testable at all in this repo's current
 * environment. This repo has no test infrastructure before this ticket and, empirically, cannot
 * run JUnit tests against {@code ServerLevel}: Mockito mocking {@code ServerLevel.class} (even
 * with the subclass mock maker) fails class initialization, because {@code ServerLevel}'s own
 * static init touches vanilla registries ({@code ResourceKey[minecraft:root /
 * minecraft:game_event]}) that require a live FML/NeoForge mod-loading context -- {@code
 * Bootstrap.bootStrap()} itself NPEs on {@code
 * net.neoforged.fml.loading.LoadingModList.get()} returning null in this bare-JUnit setup, and
 * skipping it throws "Not bootstrapped" the moment any real registry constant is touched. This
 * rules out unit-testing {@code tryGroundPlacement}/{@code tryBurial} (both take a {@code
 * ServerLevel}) here; see README.md's design decisions and the MINECRAFT-209 PR description for
 * the full writeup and what it would take to close this gap (a NeoForge GameTest, i.e. a real
 * running server -- this repo has no such harness).
 *
 * <p>{@code ItemStack} is a final class (can't be mocked); a real instance is constructed here
 * from a mocked {@link Item}/{@link BlockItem}, which does not touch any registry.
 */
class DespawnGameplayTest {

    @Test
    void isBlockItem_blockItem_true() {
        ItemStack stack = new ItemStack(mock(BlockItem.class));
        assertTrue(DespawnGameplay.isBlockItem(stack));
    }

    @Test
    void isBlockItem_nonBlockItem_false() {
        ItemStack stack = new ItemStack(mock(Item.class));
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
}
