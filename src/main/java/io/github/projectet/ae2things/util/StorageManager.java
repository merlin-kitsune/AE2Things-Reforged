package io.github.projectet.ae2things.util;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import appeng.core.AELog;

public class StorageManager extends SavedData {
    private static final Factory<StorageManager> FACTORY = new Factory<>(StorageManager::new, StorageManager::readNbt);
    private static final String DISKUUID = "disk_id";
    private final Map<UUID, DataStorage> disks;

    /**
     * Entries that could not be read, kept verbatim so that saving the side table again does not erase them. Dropping
     * them from memory would make the next unrelated disk write silently and permanently delete whatever they held.
     */
    private final List<CompoundTag> unreadable;
    @Nullable
    private WeakReference<HolderLookup.Provider> registries;

    public StorageManager() {
        disks = new HashMap<>();
        unreadable = new ArrayList<>();
        // This constructor only runs when there was no file to read, so writing the empty table back is harmless.
        this.setDirty();
    }

    private StorageManager(Map<UUID, DataStorage> disks, List<CompoundTag> unreadable) {
        this.disks = disks;
        this.unreadable = unreadable;
        // The data was just read from disk, so it does not need to be written back until something actually changes.
        // Marking it dirty here made the first world save after every load rewrite the whole side table.
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        ListTag diskList = new ListTag();
        for (Map.Entry<UUID, DataStorage> entry : disks.entrySet()) {
            CompoundTag disk = new CompoundTag();

            disk.putUUID(DISKUUID, entry.getKey());
            disk.put(Constants.DISKDATA, entry.getValue().toNbt());
            diskList.add(disk);
        }

        // Unreadable entries go back out exactly as they came in. They stay unusable until someone repairs them by
        // hand, but the file keeps every byte the player ever had.
        diskList.addAll(unreadable);

        nbt.put(Constants.DISKLIST, diskList);
        return nbt;
    }

    public static StorageManager readNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        Map<UUID, DataStorage> disks = new HashMap<>();
        List<CompoundTag> unreadable = new ArrayList<>();
        ListTag diskList = nbt.getList(Constants.DISKLIST, CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < diskList.size(); i++) {
            CompoundTag disk = diskList.getCompound(i);
            // CompoundTag#getUUID -> NbtUtils.loadUUID(Tag) dereferences the tag, so a missing key throws NPE instead
            // of returning null. hasUUID() is the only safe precondition: it checks presence, IntArrayTag type and
            // length == 4 in one go.
            if (!disk.hasUUID(DISKUUID)) {
                // An entry we cannot key is unreadable anyway, and a null key would take the map with it: getUUID()
                // throwing here used to make the dimension storage discard this whole SavedData (one "Error loading
                // saved data" line, no crash), and the next save then overwrote every other disk with an empty table.
                AELog.warn("Keeping disk entry %d from %s unreadable: no usable %s tag", i, Constants.MANAGER_NAME,
                        DISKUUID);
                unreadable.add(disk);
                continue;
            }
            // One corrupt entry must not cost us the whole side table, for the same reason.
            try {
                disks.put(disk.getUUID(DISKUUID), DataStorage.fromNbt(disk.getCompound(Constants.DISKDATA)));
            } catch (Exception e) {
                AELog.warn("Keeping disk entry %d from %s unreadable: %s", i, Constants.MANAGER_NAME, e);
                unreadable.add(disk);
            }
        }
        return new StorageManager(disks, unreadable);
    }

    public void updateDisk(UUID uuid, DataStorage dataStorage) {
        if (dataStorage == DataStorage.EMPTY) {
            throw new IllegalArgumentException("Cannot store the shared DataStorage.EMPTY sentinel for disk " + uuid);
        }
        disks.put(uuid, dataStorage);
        setDirty();
    }

    public void removeDisk(UUID uuid) {
        disks.remove(uuid);
        setDirty();
    }

    public boolean hasUUID(UUID uuid) {
        return disks.containsKey(uuid);
    }

    /**
     * Read-only lookup. Unlike {@link #getOrCreateDisk(UUID)} this never inserts a new entry, so code that merely reads
     * a disk (cell visuals, tooltips, capacity checks) cannot grow the side table or dirty the world save.
     */
    public DataStorage getDisk(UUID uuid) {
        var disk = disks.get(uuid);
        return disk != null ? disk : DataStorage.EMPTY;
    }

    public DataStorage getOrCreateDisk(UUID uuid) {
        if (!disks.containsKey(uuid)) {
            updateDisk(uuid, new DataStorage());
        }
        return disks.get(uuid);
    }

    public void modifyDisk(UUID diskID, ListTag stackKeys, long[] stackAmounts, long itemCount) {
        DataStorage diskToModify = getOrCreateDisk(diskID);
        if (stackKeys != null && stackAmounts != null) {
            if (stackKeys != diskToModify.stackKeys || stackAmounts != diskToModify.stackAmounts) {
                // The cached key index belongs to the lists that are being replaced, and an index that still points
                // at the old slot layout would make the next in-place amounts update write into the wrong slot.
                diskToModify.invalidateKeyIndex();
            }
            diskToModify.stackKeys = stackKeys;
            diskToModify.stackAmounts = stackAmounts;
        }
        diskToModify.itemCount = itemCount;

        updateDisk(diskID, diskToModify);
    }

    public static StorageManager getInstance(MinecraftServer server) {
        ServerLevel world = server.getLevel(ServerLevel.OVERWORLD);
        var manager = world.getDataStorage().computeIfAbsent(FACTORY, Constants.MANAGER_NAME);
        manager.registries = new WeakReference<>(server.registryAccess());
        return manager;
    }

    public HolderLookup.Provider getRegistries() {
        var r = this.registries;
        if (r == null) {
            throw new IllegalStateException("StorageManager was not initialized properly.");
        }

        var registries = r.get();
        if (registries == null) {
            throw new IllegalStateException("Using a StorageManager whose server was already closed");
        }

        return registries;
    }
}
