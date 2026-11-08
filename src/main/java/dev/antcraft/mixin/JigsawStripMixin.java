package dev.antcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.StripBounds;
import dev.antcraft.StripChunkGenerator;
import dev.antcraft.StripGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Villages in a strip world only use pieces that fit inside the strip. The jigsaw assembler only
 * places a piece where its bounding box fits the "free" space, so cutting that space down to the
 * strip makes it pick other houses / directions instead of building houses the walls slice in half.
 *
 * <p>Deep villages are shifted afterwards so their centre piece sits on the strip's centre x, so
 * for them the allowed x range is measured from the centre piece instead of the real strip.
 */
@Mixin(JigsawPlacement.class)
public abstract class JigsawStripMixin {
	private static final double BIG = 1.0E7;

	@ModifyVariable(
		method = "addPieces(Lnet/minecraft/world/level/levelgen/RandomState;IZLnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;Ljava/util/List;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private static VoxelShape antcraft$keepVillagesInsideStrip(
		VoxelShape shape,
		@Local(argsOnly = true) ChunkGenerator generator,
		@Local(argsOnly = true) PoolElementStructurePiece centerPiece
	) {
		if (!(generator instanceof StripChunkGenerator strip)) return shape;
		if (!centerPiece.getElement().toString().contains("village/")) return shape;
		StripBounds bounds = strip.bounds();
		double minX = bounds.minX(), maxX = bounds.maxX() + 1;
		if (StripChunkGenerator.generatingDeep()) {
			BoundingBox box = centerPiece.getBoundingBox();
			int mid = (box.minX() + box.maxX()) / 2;
			minX = mid - (bounds.centerX() - bounds.minX());
			maxX = mid + (bounds.maxX() - bounds.centerX()) + 1;
		}
		VoxelShape strip_ = Shapes.create(new AABB(minX, -BIG, bounds.minZ(), maxX, BIG, bounds.maxZ() + 1));
		return Shapes.join(shape, strip_, BooleanOp.AND);
	}
}
