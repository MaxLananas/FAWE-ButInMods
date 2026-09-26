package com.maxlananas.fawebim.fabric.client;

import com.maxlananas.fawebim.fabric.ConfigurationScreens;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/**
 * Client entrypoint of the mod.
 *
 * <p>Only the client can draw the configuration screen, and only this class knows
 * that a screen exists: it hands {@link ConfigurationScreens} an opener, and the
 * command layer calls it when a player asks for the settings. A dedicated server
 * never loads this class and keeps the chat form of the command.</p>
 */
public final class FaweModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ConfigurationScreens.setOpener(() -> {
            Minecraft client = Minecraft.getInstance();
            // The command runs on the server thread of the integrated server, so
            // the screen is opened on the client thread through its executor.
            client.execute(() -> {
                if (!(client.screen instanceof ConfigurationScreen)) {
                    client.setScreen(new ConfigurationScreen());
                }
            });
        });
    }
}
