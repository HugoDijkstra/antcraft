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

	/** English names, used when the client has no translation (index = layer). */
	private static final String[] LAYER_NAMES = {
		"The Surface", "Oak Hollow", "Taiga Deep", "Darkwood Caverns", "Jungle Grotto", "The Deep Dark", "Buried Desert", "Mangrove Mire", "Mushroom Abyss",
	};

	/** Last deep layer seen per player (server thread only). */
	private static final Map<UUID, Integer> LAST_LAYER = new HashMap<>();

	/** How far from a closed hole (horizontally, in the hall above it) the hint shows. */
	private static final int HINT_RADIUS = 5;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(LayerTracker::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_LAYER.remove(handler.player.getUUID()));
	}

	public static Component layerName(int layer) {
		return Component.translatableWithFallback("antcraft.layer." + layer, LAYER_NAMES[layer]);
	}

	private static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 10 != 0) return;
		ServerLevel overworld = server.overworld();
		if (!AntCraft.isStripLevel(overworld)) return;
		boolean titles = AntCraftConfig.get().layerTitles;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.level();
			if (AntCraft.isStripLevel(level)) award(player, "root", "in_strip");
			if (level.dimension() == Level.END) award(player, "enter_end", "entered_end");
			if (level != overworld) {
				LAST_LAYER.remove(player.getUUID());
				continue;
			}
			int layer = Strip.layerAt(player.getBlockY());
			if (layer < 0) continue; // inside a bedrock floor: keep the last layer
			Integer last = LAST_LAYER.put(player.getUUID(), layer);
			if (titles && layer >= 1 && (last == null || last != layer)) showTitle(player, layer);
			if (titles && tick % 40 == 0) {
				Component hint = holeHint(overworld, player);
				if (hint != null) player.sendOverlayMessage(hint);
			}
		}
	}

	private static void showTitle(ServerPlayer player, int layer) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatableWithFallback("antcraft.layer.subtitle", "Layer %s", layer)));
		player.connection.send(new ClientboundSetTitleTextPacket(layerName(layer)));
	}

	/** A hint when the player stands in the hall next to a hole that is still closed, else null. */
	private static @Nullable Component holeHint(ServerLevel level, ServerPlayer player) {
		StripBounds bounds = Strip.bounds(level);
		if (bounds == null) return null;
		long seed = level.getSeed();
		int y = player.getBlockY();
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			int floorTop = Strip.floorTop(k);
			if (y <= floorTop || y > floorTop + Strip.HALL_HEIGHT) continue;
			BlockPos hole = new BlockPos(Strip.holeX(seed, k, bounds), floorTop, Strip.holeZ(seed, k, bounds));
			if (Math.abs(player.getBlockX() - hole.getX()) > HINT_RADIUS || Math.abs(player.getBlockZ() - hole.getZ()) > HINT_RADIUS) continue;
			BlockState state = level.getBlockState(hole);
			if (state.is(ModContent.NETHER_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.sealed", "Sealed — a Nether Key opens it");
			}
			if (state.is(ModContent.ANCIENT_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.ancient_sealed", "Sealed — an Ancient Key opens it");
			}
			if (state.is(ModContent.GUARDIAN_SEAL)) {
				return Component.translatableWithFallback("antcraft.hint.guarded", "A guardian blocks the way");
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
