package dev.antcraft.client;

import dev.antcraft.MiningHardness;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client entrypoint: receives the server's deep mining hardness (see {@link MiningHardness}). */
public final class AntCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(MiningHardness.SyncPayload.TYPE, (payload, context) -> MiningHardness.setClient(payload.perLayer()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MiningHardness.resetClient());
	}
}
