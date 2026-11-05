package io.github.projectet.ae2things.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.projectet.ae2things.storage.DISKCellInventory;
import io.github.projectet.ae2things.storage.IDISKCellItem;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Makes the creative middle-click clone of a DISK produce an independent disk. The vanilla code path copies the item
 * stack, which would leave the clone pointing at the same stored data as the original.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class CursedInternalSlotMixin {

    @Final
    @Shadow
    public NonNullList<Slot> slots;

    @Inject(method = "doClick", at = @At(value = "INVOKE", target = "net/minecraft/world/item/ItemStack.copyWithCount (I)Lnet/minecraft/world/item/ItemStack;"), slice = @Slice(from = @At(value = "INVOKE", target = "net/minecraft/world/inventory/Slot.hasItem()Z", ordinal = 1)), cancellable = true)
    public void CLONE(int slotIndex, int button, ClickType actionType, Player player, CallbackInfo ci) {
        Slot i = this.slots.get(slotIndex);
        var stack = i.getItem();
        if (DISKCellInventory.hasDiskUUID(stack) && stack.getItem() instanceof IDISKCellItem diskCell) {
            ItemStack newStack = diskCell.cloneDisk(stack);
            this.setCarried(newStack);
            ci.cancel();
        }
    }

    @Shadow
    public abstract void setCarried(ItemStack slot);
}
