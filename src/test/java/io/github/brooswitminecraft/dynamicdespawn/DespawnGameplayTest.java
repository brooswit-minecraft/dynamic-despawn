package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicdespawn.DespawnGameplay.PlacementOffset;
import io.github.brooswitminecraft.dynamicdespawn.DespawnGameplay.SettleOutcome;
import io.github.brooswitminecraft.dynamicdespawn.DespawnGameplay.SettleResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Tests the Minecraft-free routing seams; real world/block-entity interaction needs GameTest. */
class DespawnGameplayTest {
    @Test
    void placementOrder_keepsCurrentThenProvidedRandomHorizontalOrderThenAbove() {
        List<PlacementOffset> order = DespawnGameplay.placementOrder(List.of(
            PlacementOffset.WEST, PlacementOffset.NORTH, PlacementOffset.EAST, PlacementOffset.SOUTH));

        assertEquals(List.of(PlacementOffset.CURRENT, PlacementOffset.WEST, PlacementOffset.NORTH,
            PlacementOffset.EAST, PlacementOffset.SOUTH, PlacementOffset.ABOVE), order);
    }

    @Test
    void resolvePlacement_stopsAtFirstValidCandidateAndResetsMultiStackRemainder() {
        List<Integer> attempted = new ArrayList<>();
        SettleResult result = DespawnGameplay.resolvePlacement(8, attempts(attempted, false, false, true, true));

        assertEquals(SettleOutcome.PLACED, result.outcome());
        assertTrue(result.resetRemainderTimer());
        assertEquals(List.of(0, 1, 2), attempted, "must not consume or try candidates after first placement");
    }

    @Test
    void resolvePlacement_noValidCandidate_despawnsOneAndPreservesMultiStackRemainder() {
        SettleResult result = DespawnGameplay.resolvePlacement(8, List.of(() -> false, () -> false));

        assertEquals(SettleOutcome.DESPAWNED, result.outcome());
        assertTrue(result.resetRemainderTimer());
    }

    @Test
    void resolveNonBlock_skipsFullBarrelAndUsesFirstBarrelWithCapacity() {
        AtomicInteger barrelChecks = new AtomicInteger();
        SettleResult result = DespawnGameplay.resolveNonBlock(8,
            List.of(() -> barrelChecks.incrementAndGet() == 2, () -> barrelChecks.incrementAndGet() == 2),
            List.of(() -> { throw new AssertionError("new barrel must not be created"); }));

        assertEquals(SettleOutcome.STORED, result.outcome());
        assertTrue(result.resetRemainderTimer());
        assertEquals(2, barrelChecks.get(), "the first/full barrel is skipped before available capacity is used");
    }

    @Test
    void resolveNonBlock_noExistingCapacity_createsAtFirstValidCandidate() {
        List<Integer> attempted = new ArrayList<>();
        SettleResult result = DespawnGameplay.resolveNonBlock(3, List.of(() -> false), attempts(attempted, false, true, true));

        assertEquals(SettleOutcome.STORED, result.outcome());
        assertTrue(result.resetRemainderTimer());
        assertEquals(List.of(0, 1), attempted);
    }

    @Test
    void resolveNonBlock_noStorageOrBarrelPosition_despawnsOneAndLeavesStackOfOneUntouched() {
        SettleResult result = DespawnGameplay.resolveNonBlock(1, List.of(() -> false), List.of(() -> false));

        assertEquals(SettleOutcome.DESPAWNED, result.outcome());
        assertFalse(result.resetRemainderTimer(), "a stack of one has no remainder to reset");
    }

    private static List<BooleanSupplier> attempts(List<Integer> attempted, boolean... results) {
        List<BooleanSupplier> attempts = new ArrayList<>();
        for (int index = 0; index < results.length; index++) {
            int candidate = index;
            boolean result = results[index];
            attempts.add(() -> {
                attempted.add(candidate);
                return result;
            });
        }
        return attempts;
    }
}
