package io.github.projectet.ae2things.storage;

import java.util.List;
import java.util.UUID;

import com.google.common.base.Preconditions;

import io.github.projectet.ae2things.AE2Things;
import io.github.projectet.ae2things.util.DataStorage;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.me.cells.BasicCellHandler;
import appeng.util.ConfigInventory;

public interface IDISKCellItem extends ICellWorkbenchItem {

    AEKeyType getKeyType();

    /**
     * The number of bytes that can be stored on this type of storage cell.
     * <p/>
     * It wont work if the return is not a multiple of 8. The limit is ({@link Integer#MAX_VALUE} + 1) / 8.
     *
     * @param cellItem item
     * @return number of bytes
     */
    int getBytes(ItemStack cellItem);

    /**
     * Allows you to fine tune which items are allowed on a given cell, if you don't care, just return false; As the
     * handler for this type of cell is still the default cells, the normal AE black list is also applied.
     *
     * @param cellItem          item
     * @param requestedAddition requested addition
     * @return true to preventAdditionOfItem
     */
    default boolean isBlackListed(ItemStack cellItem, AEKey requestedAddition) {
        // Only item keys can be cell items, and not every one of them actually has a cell inventory.
        if (requestedAddition instanceof AEItemKey itemKey && itemKey.getItem() instanceof IBasicCellItem) {
            var inventory = BasicCellHandler.INSTANCE.getCellInventory(itemKey.toStack(), null);
            return inventory != null && inventory.getUsedBytes() > 0;
        }
        return false;
    }

    /**
     * Allows you to specify if this storage cell can be stored inside other storage cells, only set this for special
     * items like the matter cannon that are not general purpose storage.
     *
     * @return true if the storage cell can be stored inside other storage cells, this is generally false, except for
     *         certain situations such as the matter cannon.
     */
    default boolean storableInStorageCell() {
        return false;
    }

    /**
     * Allows an item to selectively enable or disable its status as a storage cell.
     *
     * @param i item
     * @return if the ItemStack should currently be usable as a storage cell.
     */
    default boolean isStorageCell(ItemStack i) {
        return true;
    }

    /**
     * @return drain in ae/t this storage cell will use.
     */
    double getIdleDrain();

    ConfigInventory getConfigInventory(ItemStack is);

    /**
     * Clones the given disk, including its contents. Used for the creative middle click clone of the vanilla container
     * menu, which would otherwise hand out a stack that shares the contents of the original disk.
     */
    default ItemStack cloneDisk(ItemStack item) {
        var diskId = DISKCellInventory.getDiskUUID(item);
        if (diskId == null) {
            return item.copy();
        }

        var newStack = item.copy();
        UUID id = UUID.randomUUID();
        DISKCellInventory.setDiskUUID(newStack, id);
        newStack.setCount(newStack.getMaxStackSize());

        var storageManager = AE2Things.currentStorageManager();
        if (storageManager != null) {
            // The contents have to be copied as well: sharing the storage instance would make both disks change
            // whenever one of them is written to.
            DataStorage storage = storageManager.getOrCreateDisk(diskId).copy();
            DISKCellInventory.setNbtItemCount(newStack, storage.itemCount);
            storageManager.updateDisk(id, storage);
        } else {
            DISKCellInventory.removeNbtItemCount(newStack);
        }

        return newStack;
    }

    /**
     * Convenient helper to append useful tooltip information.
     */
    default void addCellInformationToTooltip(ItemStack is, List<Component> lines) {
        Preconditions.checkArgument(is.getItem() == this);
        DISKCellHandler.INSTANCE.addCellInformationToTooltip(is, lines);
    }
}
