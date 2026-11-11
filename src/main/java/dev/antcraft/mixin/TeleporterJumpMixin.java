package dev.antcraft.mixin;

import dev.antcraft.ArenaManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Teleporter controls: jumping on a lit teleporter sends the player up (the server sees every jump here). */
@Mixin(ServerPlayer.class)
public abstract class TeleporterJumpMixin {
	@Inject(method = "jumpFromGround", at = @At("TAIL"))
	private void antcraft$jumpOnTeleporter(CallbackInfo ci) {
		ArenaManager.onJump((ServerPlayer) (Object) this);
	}
}
