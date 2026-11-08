package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.StripBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Respawning the dragon clears a 21x21x21 box around each pillar top before rebuilding it. In the
 * End strip that box reaches the barrier wall: keep the wall and everything outside the strip.
 * Targets {@code DragonRespawnStage.SUMMONING_PILLARS} (the enum constant's body is class $3).
 */
@Mixin(targets = "net.minecraft.world.level.dimension.end.DragonRespawnStage$3")
public abstract class DragonRespawnMixin {
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z")
	)
	private boolean antcraft$keepWalls(ServerLevel level, BlockPos pos, boolean movedByPiston, Operation<Boolean> original) {
		StripBounds bounds = EndStripChunkGenerator.endBounds(level);
		if (bounds != null && (!bounds.inside(pos.getX(), pos.getZ()) || level.getBlockState(pos).is(Blocks.BARRIER))) {
			return false;
		}
		return original.call(level, pos, movedByPiston);
	}
}
