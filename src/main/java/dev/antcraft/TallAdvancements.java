package dev.antcraft;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The advancements of the tall layouts' new layers, guardians, key and seal (spec v2 3.10) can
 * only be earned in a tall overworld. Everywhere else (layout-1 strips, normal worlds) they are
 * taken out of the server's advancement tree, so the tree looks as before and shows no nodes that
 * can never be completed. Runs when the server has started and after every data pack reload.
 */
public final class TallAdvancements {
	private TallAdvancements() {}

	/** The new advancements (each one's children go with it). */
	static final List<String> TALL_ONLY = List.of(
		"layer_autumn_hollow", "layer_ochre_badlands", "layer_lush_grotto", "layer_trial_vault", "trial_vault_escaped", "layer_sulfur_springs",
		"layer_pale_hollow", "layer_stony_crags", "layer_crystal_hollows", "layer_sunken_reef", "layer_frozen_depths", "layer_ember_rift",
		"guardian_canyon_conjurer", "guardian_brood_mother", "guardian_gale_lord", "guardian_ember_lord", "ember_key", "opened_ember_seal"
	);

	/** The tall Nether's advancements (spec v2 4.4): only in a world whose Nether is layout 3. */
	static final List<String> TALL_NETHER_ONLY = List.of(
		"nether_depths", "guardian_hoglin_warlord", "warped_sanctuary", "guardian_molten_sovereign", "guardian_brute_warlord", "guardian_bone_colossus", "ember_forge"
	);

	/** The tall End's advancements (spec v2 5): only in a world whose End is layout 2. */
	static final List<String> TALL_END_ONLY = List.of(
		"end_ascent", "end_underside", "guardian_void_stalker", "guardian_phantom_matriarch", "void_key", "opened_void_seal", "crown_of_the_end"
	);

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(TallAdvancements::prune);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success && prune(server)) {
				// players reloaded their advancements from the full tree already: send the pruned one
				for (ServerPlayer player : server.getPlayerList().getPlayers()) player.getAdvancements().reload(server.getAdvancements());
			}
		});
	}

	static boolean isTall(MinecraftServer server) {
		StripLayout layout = Strip.layout(server.overworld());
		return layout != null && layout.id >= 2;
	}

	static boolean isTallNether(MinecraftServer server) {
		var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
		return nether != null && nether.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator gen && gen.netherLayout().tall();
	}

	static boolean isTallEnd(MinecraftServer server) {
		return EndStripChunkGenerator.tallEnd(server.getLevel(net.minecraft.world.level.Level.END)) != null;
	}

	/** Removes the tall-only advancements from the tree unless the overworld (Nether) is tall; true if any was removed. */
	private static boolean prune(MinecraftServer server) {
		List<String> hidden = new java.util.ArrayList<>();
		if (!isTall(server)) hidden.addAll(TALL_ONLY);
		if (!isTallNether(server)) hidden.addAll(TALL_NETHER_ONLY);
		if (!isTallEnd(server)) hidden.addAll(TALL_END_ONLY);
		if (hidden.isEmpty()) return false;
		var tree = server.getAdvancements().tree();
		Set<Identifier> ids = new HashSet<>();
		for (String path : hidden) {
			Identifier id = Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path);
			if (tree.get(id) != null) ids.add(id);
		}
		if (ids.isEmpty()) return false;
		tree.remove(ids);
		tree.repositionNodes(); // the old tree's layout
		AntCraft.LOGGER.info("AntCraft: {} tall-layout advancements hidden (not a tall overworld)", ids.size());
		return true;
	}
}
