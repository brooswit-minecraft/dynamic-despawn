package io.github.brooswitminecraft.dynamicdespawn;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world store of buried items, keyed by the position of the host block they were buried in.
 * This is the "block-entity store on the host block" MINECRAFT-145's decision comment calls for,
 * implemented as world-level persistent data rather than a literal vanilla block entity -- see
 * README.md's "Design decisions" section for why. The host block's own type is never changed, so
 * burial never alters world terrain beyond the item becoming recoverable by breaking that block.
 */
public final class BuriedItemsSavedData extends SavedData {
    private static final String ID = "dynamicdespawn_buried_items";

    private final HolderLookup.Provider registries;
    private final Map<BlockPos, ItemStack> buried = new HashMap<>();

    private BuriedItemsSavedData(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    public static BuriedItemsSavedData get(ServerLevel level) {
        return level.getDataStorage()
            .computeIfAbsent(
                new SavedData.Factory<>(
                    () -> new BuriedItemsSavedData(level.registryAccess()),
                    (tag, registries) -> load(tag, registries)),
                ID);
    }

    public boolean isOccupied(BlockPos pos) {
        return buried.containsKey(pos.immutable());
    }

    public void bury(BlockPos pos, ItemStack stack) {
        buried.put(pos.immutable(), stack.copy());
        setDirty();
    }

    public Optional<ItemStack> recover(BlockPos pos) {
        ItemStack stack = buried.remove(pos.immutable());
        if (stack != null) {
            setDirty();
        }
        return Optional.ofNullable(stack);
    }

    private static BuriedItemsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BuriedItemsSavedData data = new BuriedItemsSavedData(registries);
        ListTag list = tag.getList("Buried", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
            ItemStack.parse(registries, entry.getCompound("Item")).ifPresent(stack -> data.buried.put(pos, stack));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        buried.forEach((pos, stack) -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", pos.getX());
            entry.putInt("Y", pos.getY());
            entry.putInt("Z", pos.getZ());
            entry.put("Item", stack.save(this.registries));
            list.add(entry);
        });
        tag.put("Buried", list);
        return tag;
    }
}
