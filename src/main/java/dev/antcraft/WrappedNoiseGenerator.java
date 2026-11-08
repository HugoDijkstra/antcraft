package dev.antcraft;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Our generators wrap a vanilla noise generator; ChunkMap needs its settings for the RandomState. */
public interface WrappedNoiseGenerator {
	NoiseBasedChunkGenerator base();
}
