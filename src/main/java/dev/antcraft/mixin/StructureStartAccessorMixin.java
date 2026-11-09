package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.FrameGenerator;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 5. Vanilla structure starts get the chunk as their height accessor; in a framed world
 * they get the vanilla frame instead (1632..2015), so padding, "above bottom" and the jigsaw
 * height limits work as in vanilla. AntCraft's own deep starts call {@code Structure.generate}
 * themselves with their own accessor.
 */
@Mixin(ChunkGenerator.class)
public abstract class StructureStartAccessorMixin {
	@WrapOperation(
		method = "tryGenerateStructure",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/structure/Structure;generate(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"
		)
	)
	private StructureStart antcraft$frameAccessor(
		Structure structure, Holder<Structure> selected, ResourceKey<Level> dimension, RegistryAccess registryAccess, ChunkGenerator generator, BiomeSource biomeSource,
		Climate.Sampler climateSampler, RandomState randomState, StructureTemplateManager templates, long seed, ChunkPos sourceChunkPos, int references,
		LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome, Operation<StructureStart> original
	) {
		if ((Object) this instanceof FrameGenerator frame && frame.framed()) {
			heightAccessor = LevelHeightAccessor.create(frame.frameMinY(), frame.frameHeight());
		}
		return original.call(structure, selected, dimension, registryAccess, generator, biomeSource, climateSampler, randomState, templates, seed, sourceChunkPos, references, heightAccessor, validBiome);
	}
}
