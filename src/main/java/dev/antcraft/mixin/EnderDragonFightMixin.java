package dev.antcraft.mixin;

import dev.antcraft.EndStripChunkGenerator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla opens a new gateway on a ring of radius 96 after every dragon kill (20 in all). In the
 * End strip only the two slots on the z axis, (0, 96) and (0, -96), are inside the strip: the
 * others are dropped, so the first two kills open those two and later kills open none.
 */
@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin {
	@Shadow
	private ServerLevel level;

	@Shadow
	@Final
	private List<Integer> gateways;

	@Inject(method = "spawnNewGateway()V", at = @At("HEAD"))
	private void antcraft$onlyStripGateways(CallbackInfo ci) {
		if (EndStripChunkGenerator.endBounds(this.level) != null) {
			this.gateways.removeIf(g -> g != EndStripChunkGenerator.GATEWAY_NORTH && g != EndStripChunkGenerator.GATEWAY_SOUTH);
		}
	}
}
