package io.github.projectet.ae2things.storage;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.projectet.ae2things.AE2Things;
import io.github.projectet.ae2things.item.DISKDrive;
import io.github.projectet.ae2things.util.Constants;
import io.github.projectet.ae2things.util.DataStorage;
import io.github.projectet.ae2things.util.StorageManager;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.core.AELog;
import appeng.core.definitions.AEItems;
import appeng.util.ConfigInventory;
import appeng.util.prioritylist.FuzzyPriorityList;
import appeng.util.prioritylist.IPartitionList;

public class DISKCellInventory implements StorageCell {

    public static final String ITEM_COUNT_TAG = "ic";
    public static final String STACK_KEYS = "keys";
    public static final String STACK_AMOUNTS = "amts";

    private final IDISKCellItem cellType;

    private final ISaveProvider container;
    private final AEKeyType keyType;
    /**
     * Without a storage manager, this inventory is read-only and shows only information retrievable from the stack
     * itself.
     */
    @Nullable
    private final StorageManager storageManager;
    private IPartitionList partitionList;
    private IncludeExclude partitionListMode;
    private int storedItems;
    private long storedItemCount;
    private Object2LongMap<AEKey> storedAmounts;
    private final ItemStack i;
    private boolean isPersisted = true;

    /**
     * Kept for binary compatibility with AE2 MEGA Things, which creates our cell inventory with three arguments.
     */
    public DISKCellInventory(IDISKCellItem cellType, ItemStack stack, @Nullable ISaveProvider saveProvider) {
        this(cellType, stack, saveProvider, AE2Things.STORAGE_INSTANCE);
    }

    public DISKCellInventory(IDISKCellItem cellType,
            ItemStack stack,
            @Nullable ISaveProvider saveProvider,
            @Nullable StorageManager storageManager) {
        this.cellType = cellType;
        this.i = stack;
        this.container = saveProvider;
        this.keyType = cellType.getKeyType();
        this.storageManager = storageManager;
        this.storedAmounts = null;
        initData();

        updateFilter();
    }

    private void updateFilter() {
        var builder = IPartitionList.builder();

        var upgrades = getUpgradesInventory();
        var config = getConfigInventory();

        boolean hasInverter = upgrades.isInstalled(AEItems.INVERTER_CARD);
        if (upgrades.isInstalled(AEItems.FUZZY_CARD)) {
            builder.fuzzyMode(getFuzzyMode());
        }

        builder.addAll(config.keySet());

        partitionListMode = (hasInverter ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST);
        partitionList = builder.build();
    }

    /**
     * Read-only view of the backing entry. Creating an entry is left to {@link #persist()} and to modifying inserts, so
     * that merely inspecting a cell (tooltips, drive visuals, capacity probes) cannot grow the side table.
     */
    private DataStorage getDiskStorage() {
        var diskId = getDiskUUID();
        if (diskId != null && storageManager != null) {
            return storageManager.getDisk(diskId);
        } else {
            return DataStorage.EMPTY;
        }
    }

    private void initData() {
        if (hasDiskUUID()) {
            this.storedItems = getDiskStorage().stackAmounts.length;
            this.storedItemCount = getDiskStorage().itemCount;
        } else {
            this.storedItems = 0;
            this.storedItemCount = 0;
            getCellItems();
        }
    }

    public IncludeExclude getPartitionListMode() {
        return partitionListMode;
    }

    public boolean isPreformatted() {
        return !partitionList.isEmpty();
    }

    public boolean isFuzzy() {
        return partitionList instanceof FuzzyPriorityList;
    }

    public ConfigInventory getConfigInventory() {
        return this.cellType.getConfigInventory(this.i);
    }

    public FuzzyMode getFuzzyMode() {
        return this.cellType.getFuzzyMode(this.i);
    }

    public IUpgradeInventory getUpgradesInventory() {
        return this.cellType.getUpgrades(this.i);
    }

    @Override
    public CellState getStatus() {
        if (this.getStoredItemCount() == 0) {
            return CellState.EMPTY;
        }
        if (this.canHoldNewItem()) {
            return CellState.NOT_EMPTY;
        }
        return CellState.FULL;
    }

    public CellState getClientStatus() {
        return getClientStatus(this.i);
    }

    /**
     * Status that only reads the item NBT. Render paths (LED colour, tooltips) use this instead of building a whole
     * cell inventory: that would read the cell config and parse the upgrade inventory once per rendered layer per
     * frame.
     */
    public static CellState getClientStatus(ItemStack disk) {
        var itemCount = getNbtItemCount(disk);
        if (itemCount == 0) {
            return CellState.EMPTY;
        }
        if (itemCount != getTotalBytes(disk)) {
            return CellState.NOT_EMPTY;
        }
        return CellState.FULL;
    }

    @Override
    public double getIdleDrain() {
        return this.cellType.getIdleDrain();
    }

    @Override
    public void persist() {
        if (this.isPersisted) {
            return;
        }

        if (storageManager == null) {
            // Without a storage manager there is nothing to write to, so retrying on every change only wastes CPU.
            this.isPersisted = true;
            return;
        }

        var diskId = getDiskUUID();

        if (storedItemCount == 0) {
            if (diskId != null) {
                storageManager.removeDisk(diskId);
                i.removeTagKey(Constants.DISKUUID);
                i.removeTagKey(DISKCellInventory.ITEM_COUNT_TAG);
                initData();
            }
            this.isPersisted = true;
            return;
        }

        if (diskId == null) {
            // A cell can carry contents and still have no usable id: the {@code diskuuid} tag may be missing, or
            // present
            // with a value that is not a 4-int UUID (item NBT edited by hand, or copied from a save written by another
            // version of this mod). Writing those contents anywhere requires an id, so mint one here instead of quietly
            // keeping them in this instance until the cell unloads.
            diskId = UUID.randomUUID();
            setDiskUUID(i, diskId);
        }

        // Writing is the one place that may create the entry: a cell that never received anything real (a simulated
        // insert, a creative-tab probe) must not leave an empty record behind.
        var diskStorage = storageManager.getOrCreateDisk(diskId);

        // Most content changes only change the amount of a key that is already stored. Those can be written in place,
        // which avoids rebuilding the key list and re-encoding the keys. AE2 keys compare by value, so the stored key
        // list stays valid as long as no key was added or removed.
        long itemCount = diskStorage.tryUpdateAmounts(this.getCellItems());

        if (itemCount < 0) {
            // Reuse the tag of every key that was already serialized before: re-running the DFU codecs for an unchanged
            // key can only ever produce the same tag again.
            itemCount = diskStorage.rebuildFrom(this.getCellItems());

            if (diskStorage.stackKeys.isEmpty()) {
                storageManager.updateDisk(diskId, new DataStorage());
            } else {
                storageManager.modifyDisk(diskId, diskStorage.stackKeys, diskStorage.stackAmounts, itemCount);
                // Keys that are no longer on the disk do not have to be remembered.
                diskStorage.pruneKeyTags(this.getCellItems());
            }
        } else {
            storageManager.updateDisk(diskId, diskStorage);
        }

        this.storedItems = this.getCellItems().size();

        this.storedItemCount = itemCount;
        if (getNbtItemCount() != itemCount) {
            // Writing the same item count again only churns the NBT tag of the stack.
            i.getOrCreateTag().putLong(DISKCellInventory.ITEM_COUNT_TAG, itemCount);
        }

        this.isPersisted = true;
    }

    @Override
    public Component getDescription() {
        return null;
    }

    public static DISKCellInventory createInventory(ItemStack stack,
            @Nullable ISaveProvider saveProvider,
            @Nullable StorageManager storageManager) {
        Objects.requireNonNull(stack, "Cannot create cell inventory for null itemstack");

        if (!(stack.getItem() instanceof IDISKCellItem cellType)) {
            return null;
        }

        if (!cellType.isStorageCell(stack)) {
            // This is not an error. Items may decide to not be a storage cell temporarily.
            return null;
        }

        // The cell type's channel matches, so this cast is safe
        return new DISKCellInventory(cellType, stack, saveProvider, storageManager);
    }

    public boolean hasDiskUUID() {
        return hasDiskUUID(this.i);
    }

    public static boolean hasDiskUUID(ItemStack disk) {
        return disk.getItem() instanceof IDISKCellItem && disk.hasTag() && disk.getTag().contains(Constants.DISKUUID);
    }

    @Nullable
    public UUID getDiskUUID() {
        return getDiskUUID(this.i);
    }

    @Nullable
    public static UUID getDiskUUID(ItemStack disk) {
        if (!hasDiskUUID(disk)) {
            return null;
        }
        var tag = disk.getTag();
        return tag.hasUUID(Constants.DISKUUID) ? tag.getUUID(Constants.DISKUUID) : null;
    }

    public static void setDiskUUID(ItemStack disk, UUID diskId) {
        disk.getOrCreateTag().putUUID(Constants.DISKUUID, diskId);
    }

    public long getNbtItemCount() {
        return getNbtItemCount(this.i);
    }

    public static long getNbtItemCount(ItemStack disk) {
        if (!hasDiskUUID(disk)) {
            return 0;
        }
        return disk.getTag().getLong(DISKCellInventory.ITEM_COUNT_TAG);
    }

    public static void setNbtItemCount(ItemStack disk, long itemCount) {
        disk.getOrCreateTag().putLong(DISKCellInventory.ITEM_COUNT_TAG, itemCount);
    }

    public static void removeNbtItemCount(ItemStack disk) {
        disk.removeTagKey(DISKCellInventory.ITEM_COUNT_TAG);
    }

    private boolean isStorageCell(AEItemKey key) {
        var type = getStorageCell(key);
        return type != null && !type.storableInStorageCell();
    }

    private static DISKDrive getStorageCell(AEItemKey itemKey) {
        if (itemKey.getItem() instanceof DISKDrive diskDrive) {
            return diskDrive;
        }

        return null;
    }

    private static boolean isCellEmpty(DISKCellInventory inv) {
        if (inv != null) {
            return inv.getAvailableStacks().isEmpty();
        }
        return true;
    }

    protected Object2LongMap<AEKey> getCellItems() {
        if (this.storedAmounts == null) {
            this.storedAmounts = new Object2LongOpenHashMap<>();
            this.loadCellItems();
        }

        return this.storedAmounts;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var entry : this.getCellItems().object2LongEntrySet()) {
            out.add(entry.getKey(), entry.getLongValue());
        }
    }

    private void loadCellItems() {
        if (this.storageManager == null) {
            return;
        }

        var diskStorage = getDiskStorage();
        var amounts = diskStorage.stackAmounts;
        var tags = diskStorage.stackKeys;
        boolean corruptedTag = amounts.length != tags.size();
        if (corruptedTag) {
            AELog.warn("Loading storage cell with mismatched amounts/tags: %d != %d", amounts.length, tags.size());
        }

        // Only the entries that exist in both lists can be read: anything beyond the shorter of the two lists is
        // unreadable and has to be dropped instead of crashing or being silently kept around.
        int entries = Math.min(amounts.length, tags.size());

        diskStorage.resetKeyIndex();

        for (int i = 0; i < entries; i++) {
            var amount = amounts[i];
            AEKey key = AEKey.fromTagGeneric(tags.getCompound(i));

            if (amount <= 0 || key == null) {
                corruptedTag = true;
            } else {
                storedAmounts.put(key, amount);
                diskStorage.indexKey(key, i);
            }
        }

        if (corruptedTag) {
            // Rewriting the stored form also drops the entries that could not be read.
            diskStorage.invalidateKeyIndex();
            this.saveChanges();
        }
    }

    protected void saveChanges() {
        // recalculate values
        this.storedItems = this.getCellItems().size();
        this.storedItemCount = 0;
        for (var storedAmount : this.getCellItems().values()) {
            this.storedItemCount += storedAmount;
        }

        this.markChanged();
    }

    /**
     * Updates the cached counters with a known delta instead of walking the whole map, then marks the cell as changed.
     */
    private void saveChanges(long itemCountDelta) {
        this.storedItems = this.getCellItems().size();
        this.storedItemCount += itemCountDelta;

        this.markChanged();
    }

    private void markChanged() {
        this.isPersisted = false;
        if (this.container != null) {
            this.container.saveChanges();
        } else {
            // if there is no ISaveProvider, store to NBT immediately
            this.persist();
        }
    }

    public long getRemainingItemCount() {
        return this.getFreeBytes() > 0 ? this.getFreeBytes() : 0;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {

        if (amount == 0 || !keyType.contains(what)) {
            return 0;
        }

        if (storageManager == null && mode == Actionable.MODULATE) {
            // Without a storage manager the new contents could only ever live in this instance and would be gone as
            // soon as the slot is re-read, so a real insert has to be refused instead of silently eating the items.
            // Simulations are still answered, they are what the client uses to predict the result of a click.
            return 0;
        }

        if (!this.partitionList.matchesFilter(what, this.partitionListMode)) {
            return 0;
        }

        if (this.cellType.isBlackListed(this.i, what)) {
            return 0;
        }

        // This is slightly hacky as it expects a read-only access, but fine for now.
        // TODO: Guarantee a read-only access. E.g. provide an isEmpty() method and
        // ensure CellInventory does not write
        // any NBT data for empty cells instead of relying on an empty IAEStackList
        if (what instanceof AEItemKey itemKey && this.isStorageCell(itemKey)) {
            // TODO: make it work for any cell, and not just BasicCellInventory!
            var meInventory = createInventory(itemKey.toStack(), null, storageManager);
            if (!isCellEmpty(meInventory)) {
                return 0;
            }
        }

        // The id and the side-table entry are created by the first modifying insert only: a simulated insert (capacity
        // probe, client prediction) must not mint a UUID, write item NBT or dirty the world save.
        if (mode == Actionable.MODULATE && storageManager != null && !hasDiskUUID()) {
            setDiskUUID(i, UUID.randomUUID());
            storageManager.getOrCreateDisk(getDiskUUID());
            loadCellItems();
        }

        var currentAmount = this.getCellItems().getLong(what);
        long remainingItemCount = getRemainingItemCount();

        if (amount > remainingItemCount) {
            amount = remainingItemCount;
        }

        if (amount <= 0) {
            // Nothing is inserted, so do not make the cell dirty for nothing.
            return 0;
        }

        if (mode == Actionable.MODULATE) {
            getCellItems().put(what, currentAmount + amount);
            this.saveChanges(amount);
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0) {
            // Nothing is extracted, so do not make the cell dirty for nothing.
            return 0;
        }

        if (storageManager == null && mode == Actionable.MODULATE) {
            // Symmetric with insert(): without a storage manager the extraction could never be persisted, so the
            // contents would simply reappear the next time the slot is read.
            return 0;
        }

        // To avoid long-overflow on the extracting callers side
        var extractAmount = Math.min(Integer.MAX_VALUE, amount);

        var currentAmount = getCellItems().getLong(what);
        if (currentAmount > 0) {
            if (extractAmount >= currentAmount) {
                if (mode == Actionable.MODULATE) {
                    getCellItems().remove(what, currentAmount);
                    this.saveChanges(-currentAmount);
                }

                return currentAmount;
            } else {
                if (mode == Actionable.MODULATE) {
                    getCellItems().put(what, currentAmount - extractAmount);
                    this.saveChanges(-extractAmount);
                }

                return extractAmount;
            }
        }

        return 0;
    }

    public long getTotalBytes() {
        return this.cellType.getBytes(this.i);
    }

    /**
     * Capacity of a disk known only by its stack, for callers that must not build an inventory (render paths).
     */
    public static long getTotalBytes(ItemStack disk) {
        return disk.getItem() instanceof IDISKCellItem cellType ? cellType.getBytes(disk) : 0;
    }

    public long getFreeBytes() {
        return this.getTotalBytes() - this.getStoredItemCount();
    }

    public long getStoredItemCount() {
        return this.storedItemCount;
    }

    public long getStoredItemTypes() {
        return this.storedItems;
    }

    public boolean canHoldNewItem() {
        return (getFreeBytes() > 0 && getFreeBytes() != getTotalBytes());
    }
}
