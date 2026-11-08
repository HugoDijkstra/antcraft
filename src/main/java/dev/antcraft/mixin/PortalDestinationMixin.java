package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import dev.antcraft.StripBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import java.util.Optional;
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
		// both directions: the overworld and the Nether are strips at the same x/z; aim into the target's strip
		ServerLevel target = currentLevel.getServer().getLevel(currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
		StripBounds bounds = target != null ? Strip.bounds(target) : null;
		if (bounds != null) {
			return bounds.clampInside(pos, MARGIN);
		}
		return pos;
	}

	/**
	 * Vanilla searches 128 blocks for an existing overworld portal because the Nether is 8x smaller.
	 * Our Nether strip maps 1:1, so with 128 a portal far along the Nether strip would link back to
	 * some other overworld portal. Passing toNether=true gives the 16-block radius both ways (Lithium's
	 * replacement of the search uses the same rule, so this works with and without it).
	 */
	@WrapOperation(
		method = "getExitPortal",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/portal/PortalForcer;findClosestPortalPosition(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Ljava/util/Optional;"
		)
	)
	private Optional<BlockPos> antcraft$sameRadiusBothWays(
		PortalForcer forcer, BlockPos approximate, boolean toNether, WorldBorder border, Operation<Optional<BlockPos>> original, ServerLevel newLevel
	) {
		return original.call(forcer, approximate, toNether || AntCraft.isStripLevel(newLevel), border);
	}
}
