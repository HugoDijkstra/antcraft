package dev.antcraft;

import dev.antcraft.mixin.BiomeManagerAccessor;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.jspecify.annotations.Nullable;

/**
 * The terrain shift of tall layouts: the vanilla noise generator runs unchanged on a temporary
 * {@link FrameProtoChunk} whose height range is the vanilla one (-64..320 in the overworld) and
 * whose sections are the real chunk's own section objects {@code D} blocks higher. Noise,
 * aquifers, surface rules and carvers write straight into the real chunk, in their own (vanilla)
 * coordinates; nothing is copied.
 */
public final class FrameTerrain {
	private FrameTerrain() {}

	/** The vanilla overworld's height range (frame coordinates). */
	public static final LevelHeightAccessor OVERWORLD_FRAME = LevelHeightAccessor.create(-64, 384);

	/** Only used to fill missing sections, which never happens (every section of the real chunk exists); cached at server start. */
	private static volatile @Nullable PalettedContainerFactory fallbackFactory;

	/** Called at server start: a factory for when no region (registry access) is at hand. */
	public static void setFallbackFactory(RegistryAccess registryAccess) {
		fallbackFactory = PalettedContainerFactory.create(registryAccess);
	}

	/**
	 * A proto chunk over a slice of a real chunk's sections: frame y {@code f} is real y
	 * {@code f + offset}. Status EMPTY, so block writes touch only its own world-gen heightmaps,
	 * never light. It never leaves {@link #frame}'s caller (one {@code buildTerrain} future).
	 */
	public static final class FrameProtoChunk extends ProtoChunk {
		public final int offset;
		public final int sectionOffset;
		public final ChunkAccess real;

		private FrameProtoChunk(ChunkAccess real, int offset, int sectionOffset, LevelHeightAccessor frame, PalettedContainerFactory factory) {
			super(
				real.getPos(), UpgradeData.EMPTY,
				Arrays.copyOfRange(real.getSections(), sectionOffset, sectionOffset + frame.getSectionsCount()),
				new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), frame, factory, null
			);
			this.offset = offset;
			this.sectionOffset = sectionOffset;
			this.real = real;
		}
	}

	public static FrameProtoChunk frame(ChunkAccess real, int offset, LevelHeightAccessor frame, @Nullable RegistryAccess registryAccess) {
		int shift = frame.getMinY() + offset - real.getMinY();
		if ((shift & 15) != 0 || shift < 0 || (shift >> 4) + frame.getSectionsCount() > real.getSectionsCount()) {
			throw new IllegalStateException("AntCraft: frame offset " + offset + " does not fit chunk " + real.getMinY() + "+" + real.getHeight());
		}
		PalettedContainerFactory factory = fallbackFactory;
		if (factory == null && registryAccess != null) factory = PalettedContainerFactory.create(registryAccess);
		if (factory == null) throw new IllegalStateException("AntCraft: no palette factory for the terrain frame");
		return new FrameProtoChunk(real, offset, shift >> 4, frame, factory);
	}

	/**
	 * After the vanilla terrain ran in the frame: moves its world-gen heightmaps and its
	 * post-processing marks (fluids to tick) to the real chunk. Carvers already ran inside
	 * {@code buildTerrain}; the frame has no block entities, ticks or blending data.
	 */
	public static void transfer(FrameProtoChunk frame, ChunkAccess real) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
			Heightmap frameMap = frame.getOrCreateHeightmapUnprimed(type);
			Heightmap realMap = real.getOrCreateHeightmapUnprimed(type);
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					int h = frameMap.getFirstAvailable(x, z);
					if (h > frame.getMinY()) {
						realMap.update(x, h - 1 + frame.offset, z, frame.getBlockState(pos.set(x, h - 1, z)));
					}
				}
			}
		}
		ShortList[] marks = frame.getPostProcessing();
		for (int i = 0; i < marks.length; i++) {
			if (marks[i] != null && !marks[i].isEmpty()) real.addPackedPostProcess(marks[i], i + frame.sectionOffset);
		}
	}

	/**
	 * The biome manager the surface rules see inside the frame: frame y is looked up D blocks
	 * higher in the real chunks. The 3D zoom (with its y-dependent jitter) is computed in frame
	 * coordinates, so the surface sees exactly the biomes a vanilla world would.
	 * {@link #withDifferentSource} is inherited unchanged: the carvers pass the raw vanilla
	 * resolver, which already works in frame coordinates.
	 */
	public static final class ShiftedBiomeManager extends BiomeManager {
		public ShiftedBiomeManager(BiomeManager delegate, int offset) {
			super((qx, qy, qz) -> delegate.getNoiseBiomeAtQuart(qx, qy + (offset >> 2), qz), ((BiomeManagerAccessor) delegate).antcraft$getBiomeZoomSeed());
		}
	}
}
