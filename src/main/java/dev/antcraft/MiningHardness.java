package dev.antcraft;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Deep mining hardness ({@link AntCraftConfig#miningHardnessPerLayer}). The server's setting is the
 * one that counts: the server mines with its own config and sends the value to every client on
 * join, so the client's break prediction (and animation) matches. A client uses the synced value,
 * never its own config: without it, a server with a different setting would see blocks broken
 * too early (they flicker back) or let them break later than the server allows.
 */
public final class MiningHardness {
	private MiningHardness() {}

	public record SyncPayload(double perLayer) implements CustomPacketPayload {
		public static final Type<SyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "mining_hardness"));
		public static final StreamCodec<ByteBuf, SyncPayload> CODEC = ByteBufCodecs.DOUBLE.map(SyncPayload::new, SyncPayload::perLayer);

		@Override
		public Type<SyncPayload> type() {
			return TYPE;
		}
	}

	/**
	 * The value the server sent, used on the client side. 0 (vanilla speed) until a server sends
	 * one: a server without AntCraft doesn't slow down mining either.
	 */
	private static volatile double clientPerLayer = 0.0;
	private static volatile @Nullable MinecraftServer server;

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (ServerPlayNetworking.canSend(handler, SyncPayload.TYPE)) sender.sendPacket(payload());
		});
	}

	private static SyncPayload payload() {
		return new SyncPayload(AntCraftConfig.get().miningHardnessPerLayer);
	}

	/** After the config changed (Mod Menu while hosting a world): tell everyone the new value. */
	static void resync() {
		MinecraftServer current = server;
		if (current == null) return;
		current.execute(() -> {
			SyncPayload payload = payload();
			for (ServerPlayer player : current.getPlayerList().getPlayers()) {
				if (ServerPlayNetworking.canSend(player, SyncPayload.TYPE)) ServerPlayNetworking.send(player, payload);
			}
		});
	}

	/** Client side: the value received from the server (sanitised like the config). */
	public static void setClient(double perLayer) {
		clientPerLayer = Double.isFinite(perLayer) ? Math.clamp(perLayer, 0.0, AntCraftConfig.MAX_HARDNESS_PER_LAYER) : 0.0;
	}

	/** Client side: back to vanilla after leaving a server. */
	public static void resetClient() {
		clientPerLayer = 0.0;
	}

	/**
	 * How many times longer pickaxe blocks take to mine at a depth (1 = vanilla). {@code depth} is
	 * the layer scaled to layout 1's eight layers ({@link StripLayout#hardnessScale}), so the bottom
	 * of every layout is as hard as layout 1's layer 8.
	 */
	public static float factor(float depth, boolean clientSide) {
		if (depth <= 0.0F) return 1.0F;
		double perLayer = clientSide ? clientPerLayer : AntCraftConfig.get().miningHardnessPerLayer;
		return 1.0F + (float) perLayer * depth;
	}
}
