package dev.antcraft.mixin;

import dev.antcraft.ArenaManager;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Teleporter controls: starting to crouch on a lit teleporter sends the player down. (Jumping is
 * caught in {@link TeleporterJumpMixin}.) Runs on the server thread, just before the new sneak
 * state is applied, so only the press itself counts.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TeleporterInputMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handlePlayerInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setShiftKeyDown(Z)V"))
	private void antcraft$crouchOnTeleporter(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
		if (packet.input().shift() && !this.player.isShiftKeyDown()) ArenaManager.onCrouch(this.player);
	}
}
