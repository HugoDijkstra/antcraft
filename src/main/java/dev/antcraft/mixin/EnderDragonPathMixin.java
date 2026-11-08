package dev.antcraft.mixin;

import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.StripBounds;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The dragon flies between 24 path nodes on rings of radius 60, 40 and 20 around the podium. In
 * the End strip the x of every node is squashed into the strip (z keeps its full range along the
 * strip), so the circling dragon stays between the walls. The node height is looked up at the
 * squashed position too.
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonPathMixin {
	@ModifyArg(
		method = "findClosestNode()I",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;<init>(III)V"),
		index = 0
	)
	private int antcraft$squashHeightX(int x) {
		return this.antcraft$squash(x);
	}

	@ModifyArg(
		method = "findClosestNode()I",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/Node;<init>(III)V"),
		index = 0
	)
	private int antcraft$squashNodeX(int x) {
		return this.antcraft$squash(x);
	}

	private int antcraft$squash(int x) {
		StripBounds bounds = EndStripChunkGenerator.endBounds(((EnderDragon) (Object) this).level());
		if (bounds == null) return x;
		int half = Math.max(1, Math.min(bounds.maxX() - bounds.centerX(), bounds.centerX() - bounds.minX()) - 1);
		return bounds.centerX() + x * half / 60;
	}
}
