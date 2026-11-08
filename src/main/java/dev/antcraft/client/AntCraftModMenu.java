package dev.antcraft.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration ("modmenu" entrypoint). Only Mod Menu loads this class, and Mod Menu is a
 * client-only mod, so a dedicated server never touches it or the screen classes it refers to.
 */
public final class AntCraftModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AntCraftConfigScreen::new;
	}
}
