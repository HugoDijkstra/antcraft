package dev.antcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.StripBounds;
import dev.antcraft.StripChunkGenerator;
import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.block.Rotation;
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
 * <p>Deep starts are shifted afterwards so their centre piece sits on the strip's centre x, so
 * for them the allowed x range is measured from the centre piece instead of the real strip.
 *
 * <p>Tall layouts (spec v2 hooks 16 and 17): villages grow deeper (10) and up to 100 blocks along
 * the strip from their centre; the other deep jigsaw starts (trail ruins, camps, outpost) are kept
 * in the strip like the deep villages; the Trial Vault gets its own space around its start piece.
 */
@Mixin(JigsawPlacement.class)
public abstract class JigsawStripMixin {
	private static final double BIG = 1.0E7;
	private static final String ADD_PIECES =
		"addPieces(Lnet/minecraft/world/level/levelgen/RandomState;IZLnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;Ljava/util/List;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V";

	private static final String ADD_PIECES_START =
		"addPieces(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/Holder;Ljava/util/Optional;ILnet/minecraft/core/BlockPos;ZLjava/util/Optional;Lnet/minecraft/world/level/levelgen/structure/structures/JigsawStructure$MaxDistance;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/pools/DimensionPadding;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)Ljava/util/Optional;";

	/** The Trial Vault's start piece faces along the strip: its main corridor cannot grow into the walls. */
	@ModifyVariable(method = ADD_PIECES_START, at = @At("STORE"), ordinal = 0)
	private static Rotation antcraft$vaultStartRotation(Rotation rotation) {
		WorldGenShift.VaultContext vault = WorldGenShift.vault();
		return vault != null && vault.rotation() != null ? vault.rotation() : rotation;
	}

	/** Hook 16: tall layouts' villages are assembled 10 deep (vanilla 6). */
	@ModifyVariable(method = ADD_PIECES, at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static int antcraft$deeperVillages(
		int maxDepth,
		@Local(argsOnly = true) ChunkGenerator generator,
		@Local(argsOnly = true) PoolElementStructurePiece centerPiece
	) {
		if (!(generator instanceof StripChunkGenerator strip) || strip.layout().id < 2) return maxDepth;
		if (WorldGenShift.vault() != null) return Math.max(maxDepth, WorldGenShift.vault().depth());
		if (!centerPiece.getElement().toString().contains("village/")) return maxDepth;
		return Math.max(maxDepth, 10);
	}

	@ModifyVariable(method = ADD_PIECES, at = @At("HEAD"), argsOnly = true)
	private static VoxelShape antcraft$keepVillagesInsideStrip(
		VoxelShape shape,
		@Local(argsOnly = true) ChunkGenerator generator,
		@Local(argsOnly = true) PoolElementStructurePiece centerPiece
	) {
		if (!(generator instanceof StripChunkGenerator strip)) return shape;
		WorldGenShift.VaultContext vault = WorldGenShift.vault();
		if (vault != null) return Boolean.getBoolean("antcraft.dev") && Boolean.getBoolean("antcraft.vaultFree") ? shape : vaultShape(shape, vault, centerPiece.getBoundingBox());
		boolean village = centerPiece.getElement().toString().contains("village/");
		boolean tall = strip.layout().id >= 2;
		if (!village && !(tall && StripChunkGenerator.generatingDeep())) return shape;
		StripBounds bounds = strip.bounds();
		double minX = bounds.minX(), maxX = bounds.maxX() + 1;
		BoundingBox box = centerPiece.getBoundingBox();
		if (StripChunkGenerator.generatingDeep()) {
			int mid = (box.minX() + box.maxX()) / 2;
			minX = mid - (bounds.centerX() - bounds.minX());
			maxX = mid + (bounds.maxX() - bounds.centerX()) + 1;
		}
		if (tall && village) {
			// up to 100 along the strip from the centre piece (the most the reference radius allows), any jigsaw distance
			int cz = (box.minZ() + box.maxZ()) / 2;
			double minZ = Math.max(bounds.minZ(), cz - 100), maxZ = Math.min(bounds.maxZ(), cz + 100) + 1;
			AABB extent = shape.isEmpty() ? AABB.of(box) : shape.bounds();
			VoxelShape space = Shapes.create(new AABB(minX, extent.minY, minZ, maxX, extent.maxY, maxZ));
			return Shapes.join(space, Shapes.create(AABB.of(box)), BooleanOp.ONLY_FIRST);
		}
		VoxelShape strip_ = Shapes.create(new AABB(minX, -BIG, bounds.minZ(), maxX, BIG, bounds.maxZ() + 1));
		return Shapes.join(shape, strip_, BooleanOp.AND);
	}

	/**
	 * Hook 17, vault branch: the strip's width (plus an overhang of up to 6 per side) and its length
	 * minus a block at each end, measured from the start piece (the vault is moved onto the strip
	 * afterwards), within the jigsaw space, minus the start piece.
	 */
	private static VoxelShape vaultShape(VoxelShape shape, WorldGenShift.VaultContext vault, BoundingBox start) {
		StripBounds bounds = vault.bounds();
		int mid = (start.minX() + start.maxX()) / 2, midZ = (start.minZ() + start.maxZ()) / 2;
		double minX = mid - (bounds.centerX() - bounds.minX()) - vault.margin(), maxX = mid + (bounds.maxX() - bounds.centerX()) + vault.margin() + 1;
		double minZ = Math.max(midZ - (vault.vaultZ() - bounds.minZ()) + 1, midZ - 116), maxZ = Math.min(midZ + (bounds.maxZ() - vault.vaultZ()) - 1, midZ + 116) + 1;
		VoxelShape space = Shapes.create(new AABB(minX, -BIG, minZ, maxX, BIG, maxZ));
		return Shapes.join(shape, space, BooleanOp.AND);
	}
}
