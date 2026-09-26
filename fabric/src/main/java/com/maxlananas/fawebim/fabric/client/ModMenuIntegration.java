package com.maxlananas.fawebim.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The Mod Menu entrypoint: it hands the mod list the screen {@code /fawebim}
 * opens, so the settings are one click away from the mods screen.
 *
 * <p>Mod Menu is optional, and a loader only loads an entrypoint the installed
 * mod list provides: without Mod Menu this class is never touched, which is why
 * it lives on its own.</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigurationScreen::new;
    }
}
