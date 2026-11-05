package io.github.projectet.ae2things.command;

import java.util.UUID;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.github.projectet.ae2things.AE2Things;
import io.github.projectet.ae2things.item.AETItems;
import io.github.projectet.ae2things.storage.DISKCellInventory;
import io.github.projectet.ae2things.storage.IDISKCellItem;
import io.github.projectet.ae2things.util.StorageManager;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

public class Command {

    public static void commandRegister(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.getRoot().addChild(commandRoot);
        dispatcher.getRoot().addChild(recoverArg);
        dispatcher.getRoot().addChild(copyUUID);
    }

    static LiteralCommandNode<CommandSourceStack> commandRoot = Commands.literal(AE2Things.MOD_ID)
            .executes(Command::help).build();

    static LiteralCommandNode<CommandSourceStack> recoverArg = Commands.literal(AE2Things.MOD_ID)
            .then(Commands.literal("recover")
                    .then(Commands
                            .argument("uuid", UuidArgument.uuid())
                            .requires(serverCommandSource -> serverCommandSource.hasPermission(2))
                            .executes(context -> spawnDrive(context, context.getArgument("uuid", UUID.class)))))
            .build();

    static LiteralCommandNode<CommandSourceStack> copyUUID = Commands.literal(AE2Things.MOD_ID)
            .then(Commands.literal("getuuid")
                    .executes(Command::getUUID))
            .build();

    /**
     * The position of the lambdas in this method is a binary contract with AE2 MEGA Things: its mixin rewrites the only
     * string constant of {@code lambda$help$3} (the second line below) to add the {@code [DISK]} hint of its own
     * argument. Adding a lambda earlier in this class, or moving the {@code <UUID>} literal, silently breaks that help
     * text, so keep the literals exactly where they are (and keep them in English).
     */
    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("Available Argument(s): "), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/ae2things recover <UUID> - Spawns a drive with the given UUID, if it doesn't exist, does not spawn any item."),
                false);
        context.getSource().sendSuccess(() -> Component.literal(
                "/ae2things getuuid - Gets the UUID of the drive in the player's hand if it has a UUID. Returns the DISKS uuid."),
                false);
        return 0;
    }

    private static int spawnDrive(CommandContext<CommandSourceStack> context, UUID uuid) throws CommandSyntaxException {
        Player player = context.getSource().getPlayerOrException();

        StorageManager storageManager = AE2Things.currentStorageManager();

        if (storageManager != null && storageManager.hasUUID(uuid)) {
            ItemStack stack = new ItemStack(AETItems.DISK_DRIVE_256K.get());

            // Read-only lookup: the uuid was just proven to exist, so this must not be allowed to create an entry.
            var diskData = storageManager.getDisk(uuid);

            DISKCellInventory.setDiskUUID(stack, uuid);
            DISKCellInventory.setNbtItemCount(stack, diskData.itemCount);

            player.addItem(stack);

            context.getSource().sendSuccess(
                    () -> Component.translatable("command.ae2things.recover_success", player.getDisplayName(), uuid),
                    true);
            return 0;
        } else {
            context.getSource().sendFailure(Component.translatable("command.ae2things.recover_fail", uuid));
            return 1;
        }
    }

    private static int getUUID(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = context.getSource().getPlayerOrException();
        ItemStack mainStack = player.getMainHandItem();
        if (mainStack.getItem() instanceof IDISKCellItem) {
            var diskId = DISKCellInventory.getDiskUUID(mainStack);
            if (diskId != null) {
                Component text = copyToClipboard(diskId.toString());
                context.getSource().sendSuccess(() -> Component.translatable("command.ae2things.getuuid_success", text),
                        false);
                return 0;
            } else {
                context.getSource().sendFailure(Component.translatable("command.ae2things.getuuid_fail_nouuid"));
                return 1;
            }
        }
        context.getSource().sendFailure(Component.translatable("command.ae2things.getuuid_fail_notdisk"));
        return 1;
    }

    private static Component copyToClipboard(String string) {
        return Component.literal(string).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, string))
                .withHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.copy.click")))
                .withInsertion(string)
                .withColor(ChatFormatting.GREEN));
    }
}
