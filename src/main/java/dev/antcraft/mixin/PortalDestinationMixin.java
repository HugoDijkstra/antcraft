package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nether portals leading back to the overworld aim inside the strip. Without this, a portal built
 * far out in the Nether would try to exit over the void.
 */
@Mixin(NetherPortalBlock.class)
public abstract class PortalDestinationMixin {
	/** Margin keeps a freshly created 4x5 portal frame (plus its platform) off the barrier walls. */
	private static final int MARGIN = 4;

	@WrapOperation(
		method = "getPortalDestination",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/border/WorldBorder;clampToBounds(DDD)Lnet/minecraft/core/BlockPos;"
		)
	)
	private BlockPos antcraft$clampExitIntoStrip(
		WorldBorder border, double x, double y, double z, Operation<BlockPos> original, ServerLevel currentLevel
	) {
		BlockPos pos = original.call(border, x, y, z);
		// both directions: the overworld and the Nether are 20x300 strips at the same x/z
		ServerLevel target = currentLevel.getServer().getLevel(currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
		if (target != null && AntCraft.isStripLevel(target)) {
			return Strip.clampInside(pos, MARGIN);
		}
		return pos;
	}
}
