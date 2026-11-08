package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import dev.antcraft.StripBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When the game builds a new exit portal in the strip overworld, it may only use spots well inside
 * the strip, so the frame never replaces a barrier wall or hangs over the void.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
	private static final int MARGIN = 3;

	@Shadow
	@Final
	private ServerLevel level;

	@WrapOperation(
		method = "createPortal",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;isWithinBounds(Lnet/minecraft/core/BlockPos;)Z")
	)
	private boolean antcraft$onlyInsideStrip(WorldBorder border, BlockPos pos, Operation<Boolean> original) {
		boolean ok = original.call(border, pos);
		StripBounds bounds = ok ? Strip.bounds(this.level) : null;
		if (bounds != null) {
			return pos.getX() >= bounds.minX() + MARGIN && pos.getX() <= bounds.maxX() - MARGIN
				&& pos.getZ() >= bounds.minZ() + MARGIN && pos.getZ() <= bounds.maxZ() - MARGIN;
		}
		return ok;
	}

	/**
	 * Vanilla searches 128 blocks for an existing overworld portal because the Nether is 8x smaller.
	 * Our Nether strip maps 1:1, so with 128 a portal far along the Nether strip would link back to
	 * some other overworld portal. Use the same 16-block radius both ways.
	 */
	@ModifyVariable(method = "findClosestPortalPosition", at = @At("STORE"), ordinal = 0)
	private int antcraft$sameRadiusBothWays(int radius) {
		return AntCraft.isStripLevel(this.level) ? Math.min(radius, 16) : radius;
	}

	/**
	 * The search scans each column all the way down. Never accept a spot at or below the first
	 * floor's hall: the frame would replace floor bedrock. (Over an all-ocean strip the hall is the
	 * first dry space; vanilla's fallback then builds a platform at y >= 70 instead.)
	 */
	@WrapOperation(
		method = "createPortal",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/portal/PortalForcer;canHostFrame(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/core/Direction;I)Z"
		)
	)
	private boolean antcraft$notInDeepHalls(
		PortalForcer self, BlockPos origin, BlockPos.MutableBlockPos mutable, Direction direction, int offset, Operation<Boolean> original
	) {
		if (origin.getY() <= Strip.hallBottom(0) + Strip.HALL_HEIGHT + 1 && this.level.dimension() == Level.OVERWORLD && AntCraft.isStripLevel(this.level)) {
			return false;
		}
		return original.call(self, origin, mutable, direction, offset);
	}
}
