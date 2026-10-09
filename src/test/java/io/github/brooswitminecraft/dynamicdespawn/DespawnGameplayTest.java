package io.github.brooswitminecraft.dynamicdespawn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the one part of MINECRAFT-209's change that is actually testable in this repo's current
 * CI environment: the pre-existing decrement/timer-reset decision (plain {@code int}, no
 * Minecraft types involved).
 *
 * <p>Confirmed empirically (not inferred) that NO Minecraft class can be loaded in this repo's
 * plain JUnit setup, not even {@code ItemStack}: every one of {@code ServerLevel}, {@code
 * ItemStack}, and real registry constants ({@code Blocks.*}/{@code Items.*}) throws
 * "IllegalArgumentException: Not bootstrapped (... minecraft:game_event)" or an equivalent
 * class-init failure the moment its class is touched -- they all run static init code that
 * validates against vanilla's registries, which requires a live FML/NeoForge mod-loading context
 * ({@code Bootstrap.bootStrap()} itself NPEs on {@code
 * net.neoforged.fml.loading.LoadingModList.get()} returning null here). This rules out unit
 * tests entirely for {@code isBlockItem}, {@code tryGroundPlacement}, and {@code tryBurial} --
 * every one of them takes or constructs a Minecraft-typed object. Closing this gap would need a
 * NeoForge GameTest (a real running server) or the NeoForge JUnit integration this repo's
 * build.gradle does not currently set up -- out of scope for this ticket; see the MINECRAFT-209
 * PR description and ticket comments for the full diagnostic trail. In the meantime these paths
 * are verified by the Brooswit drive-test ask instead.
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
}
