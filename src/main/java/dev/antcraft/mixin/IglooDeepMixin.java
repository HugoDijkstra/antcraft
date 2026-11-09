package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.LayerTheme;
import dev.antcraft.StripChunkGenerator;
import dev.antcraft.StripLayout;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 18. Vanilla re-seats an igloo from its placeholder y 90 onto the surface when it is placed.
 * The deep igloo of a tall layout's Frozen Depths is already at its height: report the surface as
 * 91, so the re-seat moves it by 0. Surface igloos (placeholder y 54..90, layer 9) are untouched.
 */
@Mixin(IglooPieces.IglooPiece.class)
public abstract class IglooDeepMixin {
	@WrapOperation(
		method = "postProcess",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")
	)
	private int antcraft$deepIglooStays(WorldGenLevel level, Heightmap.Types type, int x, int z, Operation<Integer> original, @Local(argsOnly = true) ChunkGenerator generator) {
		if (generator instanceof StripChunkGenerator strip && strip.layout().id >= 2) {
			StripLayout layout = strip.layout();
			int layer = layout.layerAt(((StructurePiece) (Object) this).getBoundingBox().minY());
			if (layer >= 1 && layout.layer(layer).theme() == LayerTheme.FROZEN_DEPTHS) return 91;
		}
		return original.call(level, type, x, z);
	}
}
