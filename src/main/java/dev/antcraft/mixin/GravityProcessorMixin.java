package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.Strip;
import dev.antcraft.StripChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Village roads ("terrain matching" pieces) are dropped onto the heightmap, which is the real
 * surface. For a deep village, use the floor of the underground forest it was built in instead.
 */
@Mixin(GravityProcessor.class)
public abstract class GravityProcessorMixin {
	@WrapOperation(
		method = "processBlock",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")
	)
	private int antcraft$deepGround(
		LevelReader level,
		Heightmap.Types type,
		int x,
		int z,
		Operation<Integer> original,
		LevelReader levelArg,
		BlockPos targetPosition,
		BlockPos referencePos,
		BlockPos templateRelativePos,
		StructureTemplate.StructureBlockInfo processedBlockInfo,
		StructurePlaceSettings settings
	) {
		int y = processedBlockInfo.pos().getY();
		if (y < Strip.VANILLA_MIN_Y && level instanceof WorldGenLevel worldGen
			&& worldGen.getLevel().getChunkSource().getGenerator() instanceof StripChunkGenerator) {
			int layer = Strip.layerAt(y);
			if (layer >= 1) return Strip.forestFloor(layer) + 1;
		}
		return original.call(level, type, x, z);
	}
}
