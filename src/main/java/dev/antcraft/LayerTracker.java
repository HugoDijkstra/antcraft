package dev.antcraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Follows every player through the strip overworld's layers (every 10 ticks):
 * <ul>
 *   <li>a title with the layer's name when a player enters a deep layer ({@link AntCraftConfig#layerTitles});</li>
 *   <li>an action-bar hint when standing next to a hole that is still closed;</li>
 *   <li>the AntCraft advancements that can't be expressed as data (the tab's root, entering the End).</li>
 * </ul>
 * The other AntCraft advancements are plain data ({@code data/antcraft/advancement}).
 */
public final class LayerTracker {
	private LayerTracker() {}

	/** Last deep layer seen per player (server thread only). */
	private static final Map<UUID, Integer> LAST_LAYER = new HashMap<>();
	/** The same for the tall Nether's depths. */
	private static final Map<UUID, Integer> LAST_NETHER_LAYER = new HashMap<>();

	/** How far from a closed hole (horizontally, in the hall above it) the hint shows. */
	private static final int HINT_RADIUS = 5;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(LayerTracker::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			LAST_LAYER.remove(handler.player.getUUID());
			LAST_NETHER_LAYER.remove(handler.player.getUUID());
		});
	}

	/** The name of a layer: its theme's, or "The Surface" for the vanilla layer. */
	public static Component layerName(StripLayout layout, int layer) {
		LayerTheme theme = layer >= 1 ? layout.layer(layer).theme() : null;
		return theme != null ? theme.displayName() : Component.translatableWithFallback("antcraft.layer.0", "The Surface");
	}

	private static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 10 != 0) return;
		ServerLevel overworld = server.overworld();
		if (!AntCraft.isStripLevel(overworld)) return;
		StripLayout layout = Strip.layout(overworld);
		boolean titles = AntCraftConfig.get().layerTitles;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.level();
			if (AntCraft.isStripLevel(level)) award(player, "root", "in_strip");
			if (level.dimension() == Level.END) award(player, "enter_end", "entered_end");
			if (level != overworld) {
				LAST_LAYER.remove(player.getUUID());
				if (level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator nether && nether.netherLayout().tall()) {
					trackNether(player, level, nether, titles, tick);
				} else {
					LAST_NETHER_LAYER.remove(player.getUUID());
				}
				continue;
			}
			LAST_NETHER_LAYER.remove(player.getUUID());
			if (layout == null) continue; // a strip Nether or End as the overworld: no layers
			int layer = layout.layerAt(player.getBlockY());
			if (layer < 0) continue; // inside a bedrock floor: keep the last layer
			Integer last = LAST_LAYER.put(player.getUUID(), layer);
			if (layer >= 1 && (last == null || last != layer)) {
				// layer advancements are awarded by theme, so every layout uses the same ids
				LayerTheme theme = layout.layer(layer).theme();
				if (theme != null) award(player, theme.advancementId, "reached");
				if (last != null && layout.isVault(last) && layer == last + 1) award(player, "trial_vault_escaped", "escaped");
				if (titles) showTitle(player, layout, layer);
			}
			if (titles && tick % 40 == 0) {
				Component hint = holeHint(overworld, layout, player);
				if (hint != null) player.sendOverlayMessage(hint);
			}
		}
	}

	/** The tall Nether (layout 3): depth titles, the depth advancements and the boss-room hint. */
	private static void trackNether(ServerPlayer player, ServerLevel level, NetherStripChunkGenerator nether, boolean titles, int tick) {
		NetherLayout layout = nether.netherLayout();
		int layer = layout.layerAt(player.getBlockY());
		if (layer < 0) return;
		Integer last = LAST_NETHER_LAYER.put(player.getUUID(), layer);
		if (layer >= 1 && (last == null || last != layer)) {
			if (layer >= 1) award(player, "nether_depths", "reached");
			if (layer == 5) award(player, "warped_sanctuary", "reached");
			if (layer == layout.deepLayers) award(player, "ember_forge", "reached");
			if (titles) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatableWithFallback("antcraft.nether_layer.subtitle", "Nether Depth %s", layer)));
				player.connection.send(new ClientboundSetTitleTextPacket(layout.row(layer).displayName()));
			}
		}
		if (titles && tick % 40 == 0) {
			StripBounds bounds = nether.bounds();
			long seed = level.getSeed();
			int y = player.getBlockY();
			for (int k : layout.guardianFloors()) {
				int floorTop = layout.floorTop(k);
				if (y <= floorTop || y > floorTop + layout.hallHeight) continue;
				BlockPos hole = new BlockPos(layout.holeX(seed, k, bounds), floorTop, layout.holeZ(seed, k, bounds));
				if (Math.abs(player.getBlockX() - hole.getX()) > HINT_RADIUS || Math.abs(player.getBlockZ() - hole.getZ()) > HINT_RADIUS) continue;
				if (level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL)) {
					player.sendOverlayMessage(Component.translatableWithFallback("antcraft.hint.guarded", "A guardian blocks the way — press the button to fight"));
				}
			}
		}
	}

	private static void showTitle(ServerPlayer player, StripLayout layout, int layer) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
		Component subtitle = layout.isVault(layer)
			? Component.translatableWithFallback("antcraft.layer.vault_subtitle", "Layer %s — find the deepest chamber", layer)
			: Component.translatableWithFallback("antcraft.layer.subtitle", "Layer %s", layer);
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(layerName(layout, layer)));
	}

	/** A hint when the player stands in the hall next to a hole that is still closed, else null. */
	private static @Nullable Component holeHint(ServerLevel level, StripLayout layout, ServerPlayer player) {
		StripBounds bounds = Strip.bounds(level);
		if (bounds == null) return null;
		long seed = level.getSeed();
		int y = player.getBlockY();
		for (int k = 0; k < layout.floorCount; k++) {
			int floorTop = layout.floorTop(k);
			if (y <= floorTop || y > floorTop + layout.hallHeight) continue;
			BlockPos hole = new BlockPos(VaultPlan.holeX(level, layout, bounds, k), floorTop, VaultPlan.holeZ(level, layout, bounds, k));
			if (Math.abs(player.getBlockX() - hole.getX()) > HINT_RADIUS || Math.abs(player.getBlockZ() - hole.getZ()) > HINT_RADIUS) continue;
			BlockState state = level.getBlockState(hole);
			if (state.is(ModContent.NETHER_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.sealed", "Sealed — a Nether Key opens it");
			}
			if (state.is(ModContent.EMBER_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.ember_sealed", "Sealed — an Ember Key opens it");
			}
			if (state.is(ModContent.ANCIENT_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.ancient_sealed", "Sealed — an Ancient Key opens it");
			}
			if (state.is(ModContent.GUARDIAN_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.guarded", "A guardian blocks the way — press the button to fight");
			}
		}
		return null;
	}

	/** Grants one criterion of an AntCraft advancement (no-op if already done or the advancement is missing). */
	public static void award(ServerPlayer player, String advancement, String criterion) {
		AdvancementHolder holder = player.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, advancement));
		if (holder != null && !player.getAdvancements().getOrStartProgress(holder).isDone()) {
			player.getAdvancements().award(holder, criterion);
		}
	}
}
