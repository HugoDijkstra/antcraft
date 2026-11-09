package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.LayerTheme;
import dev.antcraft.StripChunkGenerator;
import dev.antcraft.StripLayout;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Hook 19. Vanilla puts an ocean ruin on the ocean floor heightmap when it is placed. A deep ruin
 * in a tall layout's Sunken Reef starts at the reef's water line: report that as the floor, and
 * vanilla's downward scan seats the ruin on the sea bed (later chunks find solid ground at once and
 * keep the height).
 *
 * <p>Also: the scan's lowest-floor start value 512 is below a tall world's moved sea floor; it is
 * raised to "no limit" (a no-op wherever the sea floor is below 512, i.e. in every other world).
 */
@Mixin(OceanRuinPieces.OceanRuinPiece.class)
public abstract class OceanRuinDeepMixin {
	@WrapOperation(
		method = "postProcess",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")
	)
	private int antcraft$deepRuinFromWaterLine(WorldGenLevel level, Heightmap.Types type, int x, int z, Operation<Integer> original, @Local(argsOnly = true) ChunkGenerator generator) {
		if (generator instanceof StripChunkGenerator strip && strip.layout().id >= 2) {
			StripLayout layout = strip.layout();
			int y = ((TemplateStructurePieceAccessor) this).antcraft$templatePosition().getY();
			int layer = layout.layerAt(y);
			if (layer >= 1 && layout.layer(layer).theme() == LayerTheme.SUNKEN_REEF) return y;
		}
		return original.call(level, type, x, z);
	}

	@ModifyConstant(method = "getHeight", constant = @Constant(intValue = 512))
	private int antcraft$noFloorLimit(int value) {
		return Integer.MAX_VALUE;
	}
}
