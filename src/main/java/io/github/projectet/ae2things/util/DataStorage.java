package io.github.projectet.ae2things.util;

import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import appeng.api.stacks.AEKey;

public class DataStorage {

    private static final String STACK_KEYS = "keys";
    private static final String STACK_AMOUNTS = "amts";
    private static final String ITEM_COUNT_TAG = "item_count";

    /**
     * Shared read-only sentinel that is used for cells without a backing disk entry. Never modify its fields: the very
     * same instance is handed out to every cell in the game.
     */
    public static final DataStorage EMPTY = new DataStorage();

    public ListTag stackKeys;
    public long[] stackAmounts;
    public long itemCount;

    /**
     * Transient cache of the serialized form of the stored keys. Encoding a key with the DFU codecs is expensive and
     * only depends on the key itself, so the result is kept until the key leaves the disk.
     */
    private final Map<AEKey, Tag> keyTags = new Object2ObjectOpenHashMap<>();

    /** Transient index of the stored keys into the parallel {@link #stackKeys}/{@link #stackAmounts} lists. */
    private final Object2IntOpenHashMap<AEKey> keyIndices = new Object2IntOpenHashMap<>();

    /** Whether {@link #keyIndices} actually describes what is stored right now. */
    private boolean keyIndicesValid;

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

    /**
     * Creates an independent copy of this storage, so that writing to one of the two does not affect the other. The
     * individual key tags are immutable and may be shared, but the list, the amounts and the item count may not.
     */
    public DataStorage copy() {
        if (this == EMPTY) {
            return new DataStorage();
        }

        var keys = new ListTag();
        for (int i = 0; i < this.stackKeys.size(); i++) {
            keys.add(this.stackKeys.getCompound(i));
        }
        return new DataStorage(keys, this.stackAmounts.clone(), this.itemCount);
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.put(STACK_KEYS, stackKeys);
        nbt.putLongArray(STACK_AMOUNTS, stackAmounts);
        if (itemCount != 0) {
            nbt.putLong(ITEM_COUNT_TAG, itemCount);
        }
        return nbt;
    }

    public static DataStorage fromNbt(CompoundTag nbt) {
        ListTag stackKeys = nbt.getList(STACK_KEYS, Tag.TAG_COMPOUND);
        long[] stackAmounts = nbt.getLongArray(STACK_AMOUNTS);
        long itemCount = nbt.getLong(ITEM_COUNT_TAG);
        return new DataStorage(stackKeys, stackAmounts, itemCount);
    }

    /**
     * Returns the serialized form of the given key, encoding and caching it if it was not stored before. Caching is
     * skipped for {@link #EMPTY}, which is a shared read-only instance.
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
     * Drops the cached tags of all keys that are not stored anymore.
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

    /**
     * Empties the key index. Called before the stored key list is rebuilt from scratch.
     */
    public void resetKeyIndex() {
        if (this == EMPTY) {
            return;
        }
        keyIndices.clear();
        keyIndicesValid = true;
    }

    /**
     * Records where a stored key lives in {@link #stackKeys}/{@link #stackAmounts}.
     */
    public void indexKey(AEKey key, int index) {
        if (this == EMPTY) {
            return;
        }
        keyIndices.put(key, index);
    }

    /**
     * Marks the key index as outdated, so that the next persist rebuilds the stored key list instead of updating it in
     * place.
     */
    public void invalidateKeyIndex() {
        if (this == EMPTY) {
            return;
        }
        keyIndices.clear();
        keyIndicesValid = false;
    }

    /**
     * Updates the stored amounts in place, which avoids rebuilding the stored key list in the common case of a cell
     * whose contents changed without any key being added or removed.
     *
     * @return the new total item count, or {@code -1} if the stored key list does not match the given amounts anymore
     *         and has to be rebuilt with {@link #rebuildFrom}.
     */
    public long tryUpdateAmounts(Object2LongMap<AEKey> amounts) {
        if (this == EMPTY || !keyIndicesValid || keyIndices.size() != amounts.size()) {
            return -1;
        }

        long itemCount = 0;
        for (var entry : amounts.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount <= 0 || !keyIndices.containsKey(entry.getKey())) {
                return -1;
            }
            itemCount += amount;
        }

        for (var entry : amounts.object2LongEntrySet()) {
            this.stackAmounts[keyIndices.getInt(entry.getKey())] = entry.getLongValue();
        }
        this.itemCount = itemCount;

        return itemCount;
    }

    /**
     * Rebuilds the stored key list from the given amounts, reusing the cached key tags, and refreshes the key index.
     *
     * @return the new total item count
     */
    public long rebuildFrom(Object2LongMap<AEKey> amounts, HolderLookup.Provider registries) {
        if (this == EMPTY) {
            throw new IllegalStateException("Cannot write to the shared DataStorage.EMPTY sentinel");
        }

        long itemCount = 0;
        var keys = new ListTag();
        var amountsList = new LongArrayList(amounts.size());

        resetKeyIndex();

        for (var entry : amounts.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount > 0) {
                itemCount += amount;
                keys.add(getOrEncodeKeyTag(entry.getKey(), registries));
                amountsList.add(amount);
                indexKey(entry.getKey(), keys.size() - 1);
            }
        }

        this.stackKeys = keys;
        this.stackAmounts = amountsList.toArray(new long[0]);
        this.itemCount = itemCount;

        return itemCount;
    }
}
