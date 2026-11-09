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
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
		ServerLevel target = currentLevel.getServer().getLevel(currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
		// tall layouts (spec 4.5): y + (D_N - D_OW) into the Nether (+208), the reverse back; 0 for old worlds
		int dy = antcraft$yShift(currentLevel, target);
		if (dy != 0 && target != null) y = Math.clamp(y + dy, target.getMinY(), target.getMaxY());
		BlockPos pos = original.call(border, x, y, z);
		// both directions: the overworld and the Nether are strips at the same x/z; aim into the target's strip
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
		if (antcraft$tall(newLevel)) {
			// Rule 2: link to an existing portal at any depth within 16 blocks (x/z), nearest in y first.
			// A plain call (Lithium replaces findClosestPortalPosition, but not this search).
			var poi = newLevel.getPoiManager();
			poi.ensureLoadedAndValid(newLevel, approximate, 16);
			return poi.getInSquare(type -> type.is(PoiTypes.NETHER_PORTAL), approximate, 16, PoiManager.Occupancy.ANY)
				.map(PoiRecord::getPos)
				.filter(border::isWithinBounds)
				.filter(p -> newLevel.getBlockState(p).hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
				.min(Comparator.<BlockPos>comparingInt(p -> Math.abs(p.getY() - approximate.getY()))
					.thenComparingDouble(p -> Math.pow(p.getX() - approximate.getX(), 2) + Math.pow(p.getZ() - approximate.getZ(), 2)));
		}
		return original.call(forcer, approximate, toNether || AntCraft.isStripLevel(newLevel), border);
	}

	/** True if a portal target level is a tall strip (overworld layout 2, Nether layout 3) whose other side is tall too. */
	private static boolean antcraft$tall(ServerLevel level) {
		ServerLevel other = level.getServer().getLevel(level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
		return other != null && antcraft$yShift(other, level) != 0;
	}

	/** How far y moves from {@code from} to {@code to}: D(to) - D(from) when both are framed strips, else 0. */
	private static int antcraft$yShift(ServerLevel from, @org.jspecify.annotations.Nullable ServerLevel to) {
		if (to == null) return 0;
		var a = from.getChunkSource().getGenerator();
		var b = to.getChunkSource().getGenerator();
		if (!(a instanceof dev.antcraft.FrameGenerator fa) || !(b instanceof dev.antcraft.FrameGenerator fb) || !fa.framed() || !fb.framed()) return 0;
		if (!(a instanceof dev.antcraft.StripGenerator) || !(b instanceof dev.antcraft.StripGenerator)) return 0;
		return fb.frameOffset() - fa.frameOffset();
	}
}
