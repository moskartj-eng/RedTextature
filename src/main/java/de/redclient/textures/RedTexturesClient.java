package de.redclient.textures;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class RedTexturesClient implements ClientModInitializer {
    private static boolean pendingOpen;

    @Override
    public void onInitializeClient() {
        RedConfig.load();

        // /redmenu
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("redmenu").executes(ctx -> {
                    pendingOpen = true;
                    return 1;
                })));

        // Screen erst im naechsten Tick oeffnen, damit der Chat sich nicht darueber schliesst.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingOpen && client.currentScreen == null) {
                pendingOpen = false;
                client.setScreen(new RedMenuScreen());
            }
        });
    }
}
