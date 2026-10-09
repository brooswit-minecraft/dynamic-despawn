package io.github.brooswitminecraft.dynamicdespawn;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The {@link DespawnGameplay#onItemExpire} burial path's view of {@link BuriedItemsSavedData} --
 * narrowed to just the two operations burial needs, so tests can supply an in-memory fake instead
 * of a real {@code ServerLevel}'s persistent data storage.
 */
interface BurialStore {
    boolean isOccupied(BlockPos pos);

    void bury(BlockPos pos, ItemStack stack);
}
