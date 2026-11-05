package io.github.projectet.ae2things.client;

import io.github.projectet.ae2things.item.AETItems;
import io.github.projectet.ae2things.item.DISKDrive;

import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

public class AE2ThingsClient {
    public static void init() {
        var eventBus = FMLJavaModLoadingContext.get().getModEventBus();
        eventBus.addListener(AE2ThingsClient::registerItemColors);
    }

    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(DISKDrive::getColor, AETItems.DISK_DRIVE_1K.get(),
                AETItems.DISK_DRIVE_4K.get(), AETItems.DISK_DRIVE_16K.get(), AETItems.DISK_DRIVE_64K.get(),
                AETItems.DISK_DRIVE_256K.get());
    }
}
