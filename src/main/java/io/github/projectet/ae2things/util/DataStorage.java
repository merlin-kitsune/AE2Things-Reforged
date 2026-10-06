package io.github.projectet.ae2things.util;

import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import appeng.api.stacks.AEKey;

public class DataStorage {

    private static final String STACK_KEYS = "keys";
    private static final String STACK_AMOUNTS = "amts";
    private static final String ITEM_COUNT_TAG = "item_count";

    public static final DataStorage EMPTY = new DataStorage();

    public ListTag stackKeys;
    public long[] stackAmounts;
    public long itemCount;

    /**
     * Transient cache of the serialized form of the keys that are currently stored on this disk.
     * <p>
     * AE2 keys compare by value (an item key is the item plus its data component patch), so equal keys always serialize
     * to the same tag. Keeping that tag around makes re-serializing an unchanged disk a plain list rebuild instead of
     * running the DFU codecs (including the data component patch codec) for every key again.
     * <p>
     * This is never written to NBT and it is pruned whenever keys are removed from the disk, so it cannot grow beyond
     * the number of keys the disk itself keeps in memory.
     */
    private final Map<AEKey, Tag> keyTags = new Object2ObjectOpenHashMap<>();

    public DataStorage() {
        stackKeys = new ListTag();
        stackAmounts = new long[0];
        itemCount = 0;
    }

    public DataStorage(ListTag stackKeys, long[] stackAmounts, long itemCount) {
        this.stackKeys = stackKeys;
        this.stackAmounts = stackAmounts;
        this.itemCount = itemCount;
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.put(STACK_KEYS, stackKeys);
        nbt.putLongArray(STACK_AMOUNTS, stackAmounts);
        if (itemCount != 0)
            nbt.putLong(ITEM_COUNT_TAG, itemCount);

        return nbt;
    }

    public static DataStorage fromNbt(CompoundTag nbt) {
        ListTag stackKeys = nbt.getList(STACK_KEYS, Tag.TAG_COMPOUND);
        long[] stackAmounts = nbt.getLongArray(STACK_AMOUNTS);
        long itemCount = nbt.getLong(ITEM_COUNT_TAG);

        return new DataStorage(stackKeys, stackAmounts, itemCount);
    }

    /**
     * Returns the serialized form of the given key, encoding and caching it the first time it is seen.
     * <p>
     * Caching is skipped for {@link #EMPTY}, which is a shared read-only sentinel that must never be written to.
     *
     * @param key        the key to serialize
     * @param registries the registries used for serialization
     * @return the serialized key
     */
    public Tag getOrEncodeKeyTag(AEKey key, HolderLookup.Provider registries) {
        if (this == EMPTY) {
            return key.toTagGeneric(registries);
        }

        var tag = keyTags.get(key);
        if (tag == null) {
            tag = key.toTagGeneric(registries);
            keyTags.put(key, tag);
        }

        return tag;
    }

    /**
     * Drops the cached tags of keys that are no longer stored on this disk.
     *
     * @param storedAmounts the amounts of the keys that are currently stored on the disk
     */
    public void pruneKeyTags(Object2LongMap<AEKey> storedAmounts) {
        if (keyTags.size() > storedAmounts.size()) {
            var iterator = keyTags.keySet().iterator();
            while (iterator.hasNext()) {
                if (!storedAmounts.containsKey(iterator.next())) {
                    iterator.remove();
                }
            }
        }
    }
}
