package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.NetherLayout;
import dev.antcraft.NetherStripChunkGenerator;
import dev.antcraft.Strip;
import dev.antcraft.StripBounds;
import dev.antcraft.StripLayout;
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
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

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
		StripLayout layout = this.level.dimension() == Level.OVERWORLD ? Strip.layout(this.level) : null;
		if (layout != null && origin.getY() <= layout.hallBottom(0) + layout.hallHeight + 1) {
			return false;
		}
		return original.call(self, origin, mutable, direction, offset);
	}

	/** The tall strip overworld's layout, or null (layout 1 keeps vanilla's numbers). */
	private StripLayout antcraft$tall() {
		StripLayout layout = this.level.dimension() == Level.OVERWORLD ? Strip.layout(this.level) : null;
		return layout != null && layout.frameOffset != 0 ? layout : null;
	}

	/** The tall Nether (layout 3), or null. */
	private NetherLayout antcraft$tallNether() {
		return this.level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator nether && nether.netherLayout().tall() ? nether.netherLayout() : null;
	}

	/**
	 * Hook 14 (rule 1, banded creation): in a tall overworld new portals are built in the moved
	 * vanilla band only, 1643..2015, the fallback platform at 70 + 1696 (at most 2006); in the tall
	 * Nether in 1915..2026 (above the NF0 tunnel, under the bedrock roof), the platform at 1974 (at most 2017).
	 */
	@WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
	private int antcraft$bandBottom(ServerLevel level, Operation<Integer> original) {
		int y = original.call(level);
		StripLayout layout = this.antcraft$tall();
		if (layout != null) return Math.max(y, layout.hallBottom(0) + layout.hallHeight + 1);
		NetherLayout nether = this.antcraft$tallNether();
		return nether == null ? y : Math.max(y, NETHER_BAND_MIN);
	}

	@WrapOperation(method = "createPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
	private int antcraft$bandTop(ServerLevel level, Operation<Integer> original) {
		int y = original.call(level);
		StripLayout layout = this.antcraft$tall();
		if (layout != null) return Math.min(y, layout.vanillaMinY + 384 - 1);
		NetherLayout nether = this.antcraft$tallNether();
		return nether == null ? y : Math.min(y, NETHER_BAND_MAX);
	}

	@ModifyConstant(method = "createPortal", constant = @Constant(intValue = 70))
	private int antcraft$platformHeight(int value) {
		StripLayout layout = this.antcraft$tall();
		if (layout != null) return value + layout.frameOffset;
		NetherLayout nether = this.antcraft$tallNether();
		return nether == null ? value : value + nether.frameOffset;
	}

	private static final int NETHER_BAND_MIN = 1915, NETHER_BAND_MAX = 2026;
}
