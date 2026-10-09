package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicdespawn.DespawnGameplay.SettleOutcome;
import io.github.brooswitminecraft.dynamicdespawn.DespawnGameplay.SettleResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Covers the part of MINECRAFT-209's change that is testable in this repo's current CI
 * environment: the pre-existing decrement/timer-reset decision, and (per the PR #4 review) the
 * ground-before-burial orchestration seam ({@link DespawnGameplay#settle}), extracted
 * specifically so it can be exercised with plain {@code BooleanSupplier} args instead of real
 * Minecraft types.
 *
 * <p>Confirmed empirically (not inferred) that NO Minecraft class can be loaded in this repo's
 * plain JUnit setup, not even {@code ItemStack}: every one of {@code ServerLevel}, {@code
 * ItemStack}, and real registry constants ({@code Blocks.*}/{@code Items.*}) throws
 * "IllegalArgumentException: Not bootstrapped (... minecraft:game_event)" or an equivalent
 * class-init failure the moment its class is touched -- they all run static init code that
 * validates against vanilla's registries, which requires a live FML/NeoForge mod-loading context
 * ({@code Bootstrap.bootStrap()} itself NPEs on {@code
 * net.neoforged.fml.loading.LoadingModList.get()} returning null here). This rules out unit
 * tests entirely for {@code isBlockItem}, {@code tryGroundPlacement}, and {@code tryBurial}
 * themselves -- every one of them takes or constructs a Minecraft-typed object. Closing that gap
 * would need a NeoForge GameTest (a real running server) or the NeoForge JUnit integration this
 * repo's build.gradle does not currently set up -- out of scope for this ticket; see the
 * MINECRAFT-209 PR description and ticket comments for the full diagnostic trail. The
 * ground-before-burial ORDERING and the outcome/remainder-timer DECISION are covered here; the
 * world-interaction paths themselves (actually placing a block, actually burying) are verified by
 * the Brooswit drive-test ask instead.
 */
class DespawnGameplayTest {

    @Test
    void shouldResetRemainderTimer_stackGreaterThanOne_true() {
        assertTrue(DespawnGameplay.shouldResetRemainderTimer(8));
    }

    @Test
    void shouldResetRemainderTimer_stackOfOne_false() {
        assertFalse(DespawnGameplay.shouldResetRemainderTimer(1));
    }

    @Test
    void settle_groundSucceeds_buryNeverCalled() {
        AtomicInteger buryCalls = new AtomicInteger();
        SettleResult result = DespawnGameplay.settle(8, () -> true, () -> {
            buryCalls.incrementAndGet();
            return true;
        });

        assertEquals(SettleOutcome.PLACED, result.outcome());
        assertEquals(0, buryCalls.get(), "ground succeeding must short-circuit burial, not just run it in order");
        assertTrue(result.resetRemainderTimer());
    }

    @Test
    void settle_groundFails_buryIsCalledAndSucceeds() {
        AtomicInteger buryCalls = new AtomicInteger();
        SettleResult result = DespawnGameplay.settle(8, () -> false, () -> {
            buryCalls.incrementAndGet();
            return true;
        });

        assertEquals(SettleOutcome.BURIED, result.outcome());
        assertEquals(1, buryCalls.get());
        assertTrue(result.resetRemainderTimer());
    }

    @Test
    void settle_groundAndBuryBothFail_outcomeNoneAndNoRemainderReset() {
        SettleResult result = DespawnGameplay.settle(8, () -> false, () -> false);

        assertEquals(SettleOutcome.NONE, result.outcome());
        assertFalse(result.resetRemainderTimer(), "no settlement happened, so there is nothing to reset a timer for");
    }

    @Test
    void settle_stackOfOne_placedLeavesNoRemainderToReset() {
        SettleResult result = DespawnGameplay.settle(1, () -> true, () -> false);

        assertEquals(SettleOutcome.PLACED, result.outcome());
        assertFalse(result.resetRemainderTimer(), "a stack of 1 leaves no remainder, so its timer is never reset");
    }

    @Test
    void settle_stackGreaterThanOne_buriedResetsRemainderTimer() {
        SettleResult result = DespawnGameplay.settle(3, () -> false, () -> true);

        assertEquals(SettleOutcome.BURIED, result.outcome());
        assertTrue(result.resetRemainderTimer());
    }
}
