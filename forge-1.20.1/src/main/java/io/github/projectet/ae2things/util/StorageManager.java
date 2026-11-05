package io.github.projectet.ae2things.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import appeng.core.AELog;

public class StorageManager extends SavedData {
    private final Map<UUID, DataStorage> disks;

    public StorageManager() {
        disks = new HashMap<>();
        this.setDirty();
    }

    private StorageManager(Map<UUID, DataStorage> disks) {
        this.disks = disks;
        // The data was just read from disk, so it does not need to be written back until something actually changes.
        // Marking it dirty here made every world save rewrite the whole side table.
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag diskList = new ListTag();
        for (Map.Entry<UUID, DataStorage> entry : disks.entrySet()) {
            CompoundTag disk = new CompoundTag();

            disk.putUUID(Constants.DISKUUID, entry.getKey());
            disk.put(Constants.DISKDATA, entry.getValue().toNbt());
            diskList.add(disk);
        }

        nbt.put(Constants.DISKLIST, diskList);
        return nbt;
    }

    public static StorageManager readNbt(CompoundTag nbt) {
        Map<UUID, DataStorage> disks = new HashMap<>();
        ListTag diskList = nbt.getList(Constants.DISKLIST, CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < diskList.size(); i++) {
            CompoundTag disk = diskList.getCompound(i);
            // CompoundTag#getUUID -> NbtUtils.loadUUID(Tag) dereferences the tag, so a missing key throws NPE
            // instead of returning null (verified on the 1.20.1 merged jar with javap). hasUUID() is the only
            // safe precondition: it checks presence, IntArrayTag type and length == 4 in one go.
            if (!disk.hasUUID(Constants.DISKUUID)) {
                // An entry we cannot key is unreadable anyway, and storing a null key would kill the next save.
                AELog.warn("Dropping disk entry %d from %s: no usable %s tag", i, Constants.MANAGER_NAME,
                        Constants.DISKUUID);
                continue;
            }
            // One corrupt entry must not cost us the whole side table: readNbt throwing would make the
            // dimension storage discard the SavedData and overwrite every other disk on the next save.
            try {
                disks.put(disk.getUUID(Constants.DISKUUID), DataStorage.fromNbt(disk.getCompound(Constants.DISKDATA)));
            } catch (Exception e) {
                AELog.warn("Dropping unreadable disk entry %d from %s: %s", i, Constants.MANAGER_NAME, e);
            }
        }
        return new StorageManager(disks);
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
        return world.getDataStorage().computeIfAbsent(StorageManager::readNbt, StorageManager::new,
                Constants.MANAGER_NAME);
    }
}
