package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.StripBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Players entering the End arrive at (0, 50, 100) in the End strip instead of vanilla's (100, 50, 0),
 * which lies outside it. The portal builds the obsidian platform under that point on arrival, so the
 * vanilla worldgen platform (clipped away with the rest of x = 100) is not needed.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalSpawnMixin {
	@ModifyExpressionValue(
		method = "getPortalDestination",
		at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;END_SPAWN_POINT:Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos antcraft$stripEndSpawn(BlockPos original, @Local(argsOnly = true) ServerLevel currentLevel) {
		ServerLevel end = currentLevel.getServer().getLevel(Level.END);
		StripBounds bounds = end != null ? EndStripChunkGenerator.endBounds(end) : null;
		return bounds != null ? EndStripChunkGenerator.spawnPoint(bounds) : original;
	}
}
