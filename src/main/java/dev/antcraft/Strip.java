package dev.antcraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * Entry points to the AntCraft world's geometry. The x/z footprint is per world and per dimension
 * ({@link StripBounds}); the vertical layout of the strip overworld is a {@link StripLayout}, saved
 * with the world's generator.
 */
public final class Strip {
	private Strip() {}

	/** Dimension type of tall strip overworlds (a client tells the layout from it). */
	public static final ResourceKey<DimensionType> TALL_OVERWORLD_TYPE = ResourceKey.create(
		Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_overworld_tall")
	);

	/**
	 * The x/z footprint of a strip dimension, or null if the level is not a strip. Each strip
	 * generator carries its own bounds (overworld and Nether share the world's width and length).
	 */
	public static @Nullable StripBounds bounds(Level level) {
		return level instanceof ServerLevel server ? bounds(server.getChunkSource().getGenerator()) : null;
	}

	public static @Nullable StripBounds bounds(ChunkGenerator generator) {
		return generator instanceof StripGenerator strip ? strip.bounds() : null;
	}

	/**
	 * The overworld layout of a level. Server: the strip overworld generator's layout, null for any
	 * other level. Client: from the (synced) dimension type, layout 1 for anything that is not a
	 * tall strip overworld.
	 */
	public static @Nullable StripLayout layout(Level level) {
		if (level instanceof ServerLevel server) return layout(server.getChunkSource().getGenerator());
		return level.dimensionTypeRegistration().is(TALL_OVERWORLD_TYPE) ? StripLayout.byId(StripLayout.LATEST) : StripLayout.LAYOUT_1;
	}

	public static @Nullable StripLayout layout(ChunkGenerator generator) {
		return generator instanceof StripChunkGenerator strip ? strip.layout() : null;
	}

	/** The layout for depth effects in an overworld (mining, ...): layout 1 unless it is a known strip layout. */
	public static StripLayout layoutOrDefault(Level level) {
		StripLayout layout = layout(level);
		return layout != null ? layout : StripLayout.LAYOUT_1;
	}

	/** How far the generator moves the vanilla world up (0 for non-strip generators and old layouts). */
	public static int frameOffset(ChunkGenerator generator) {
		return generator instanceof FrameGenerator frame ? frame.frameOffset() : 0;
	}

	/**
	 * How many times longer pickaxe blocks take to mine in a deep layer (1 = vanilla). The client
	 * uses the server's value, see {@link MiningHardness}.
	 */
	public static float hardness(StripLayout layout, int layer, boolean clientSide) {
		return MiningHardness.factor(layer <= 0 ? 0.0F : layout.hardnessScale(layer), clientSide);
	}

	// ---------------------------------------------------------------- seeded positions

	/** Deterministic 64-bit mix (SplitMix64 finalizer). */
	public static long mix(long seed, long salt) {
		long z = seed + salt * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
