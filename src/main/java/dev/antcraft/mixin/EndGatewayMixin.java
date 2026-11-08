package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.Strip;
import dev.antcraft.StripBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * End gateways in the End strip:
 * <ul>
 *   <li>the exit search of a new gateway aims at the outer islands inside the strip (0, -1100)
 *       instead of 1024 blocks out along the gateway's direction;
 *   <li>the barrier walls never count as the "tallest block" (they reach the top of the world);
 *   <li>the new exit gateway stays 2 blocks off the walls;
 *   <li>a gateway whose exit lies outside the strip (the outer-island return gateways point at
 *       vanilla's spawn 100, 50, 0) sends you to the strip's arrival platform instead.
 * </ul>
 */
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class EndGatewayMixin {
	@WrapOperation(
		method = "findOrCreateValidTeleportPos",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;findExitPortalXZPosTentative(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"
		)
	)
	private static Vec3 antcraft$exitInsideStrip(ServerLevel level, BlockPos gatewayPos, Operation<Vec3> original) {
		StripBounds bounds = EndStripChunkGenerator.endBounds(level);
		if (bounds == null) return original.call(level, gatewayPos);
		return new Vec3(bounds.centerX(), 0.0, EndStripChunkGenerator.GATEWAY_EXIT_Z);
	}

	@WrapOperation(
		method = "findTallestBlock",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;isCollisionShapeFullBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"
		)
	)
	private static boolean antcraft$ignoreWalls(BlockState state, BlockGetter level, BlockPos pos, Operation<Boolean> original) {
		if (state.is(Blocks.BARRIER) && level instanceof Level l && Strip.bounds(l) != null) return false;
		return original.call(state, level, pos);
	}

	@WrapOperation(
		method = "getPortalPosition",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;findOrCreateValidTeleportPos(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
		)
	)
	private BlockPos antcraft$keepOffWalls(ServerLevel level, BlockPos gatewayPos, Operation<BlockPos> original) {
		BlockPos pos = original.call(level, gatewayPos);
		StripBounds bounds = EndStripChunkGenerator.endBounds(level);
		return bounds != null ? bounds.clampInside(pos, 2) : pos;
	}

	@ModifyReturnValue(method = "getPortalPosition", at = @At("RETURN"))
	private @Nullable Vec3 antcraft$exitInStrip(@Nullable Vec3 exit, @Local(argsOnly = true) ServerLevel level) {
		if (exit == null) return null;
		StripBounds bounds = EndStripChunkGenerator.endBounds(level);
		if (bounds == null || bounds.inside((int) Math.floor(exit.x), (int) Math.floor(exit.z))) return exit;
		BlockPos spawn = EndStripChunkGenerator.spawnPoint(bounds);
		EndPlatformFeature.createEndPlatform(level, spawn.below(), true);
		return Vec3.atBottomCenterOf(spawn);
	}
}
