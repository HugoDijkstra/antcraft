package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.WrappedNoiseGenerator;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ChunkMap only builds a real RandomState (climate noise, aquifers, ...) for an
 * {@code instanceof NoiseBasedChunkGenerator}. Ours wraps one, so without this the overworld's
 * climate would collapse to a single biome.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/RandomState;create(Lnet/minecraft/core/HolderGetter;JZLnet/minecraft/world/level/block/state/BlockState;ILnet/minecraft/world/level/levelgen/NoiseRouter;)Lnet/minecraft/world/level/levelgen/RandomState;"
		)
	)
	private RandomState antcraft$useWrappedNoiseSettings(
		HolderGetter<NormalNoise> noises,
		long seed,
		boolean legacyRandom,
		BlockState defaultBlock,
		int seaLevel,
		NoiseRouter router,
		Operation<RandomState> original,
		@Local(argsOnly = true) ChunkGenerator generator
	) {
		if (generator instanceof WrappedNoiseGenerator wrapped) {
			return RandomState.create(noises, seed, wrapped.base().generatorSettings().value());
		}
		return original.call(noises, seed, legacyRandom, defaultBlock, seaLevel, router);
	}
}
