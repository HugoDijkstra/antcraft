package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The playable x/z footprint of one strip dimension, inclusive. The barrier wall runs one block
 * outside it. Every strip generator carries its own bounds (see {@link StripGenerator}); look them
 * up with {@link Strip#bounds}.
 */
public record StripBounds(int minX, int maxX, int minZ, int maxZ) {
	public static final int MIN_WIDTH = 18, MAX_WIDTH = 48; // the End portal room is 18 wide
	public static final int MIN_LENGTH = 150, MAX_LENGTH = 1000;
	public static final int DEFAULT_WIDTH = 20, DEFAULT_LENGTH = 300;

	/**
	 * The {@code width} / {@code length} fields of a strip generator (overworld and Nether
	 * footprint). They are always written when a world is saved, so a world keeps its size.
	 *
	 * <p>When they are missing: with {@code "size_from_config": true} (the antcraft:strip world
	 * preset) a new world takes the size from {@link AntCraftConfig}; without it, the world is an
	 * older AntCraft world from before the size was configurable, which is always 20 x 300.
	 */
	public static final MapCodec<StripBounds> SIZE_CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				Codec.intRange(MIN_WIDTH, MAX_WIDTH).optionalFieldOf("width").forGetter(b -> Optional.of(b.width())),
				Codec.intRange(MIN_LENGTH, MAX_LENGTH).optionalFieldOf("length").forGetter(b -> Optional.of(b.length())),
				Codec.BOOL.optionalFieldOf("size_from_config", false).forGetter(b -> false)
			)
			.apply(i, StripBounds::resolveSize)
	);

	private static StripBounds resolveSize(Optional<Integer> width, Optional<Integer> length, boolean fromConfig) {
		AntCraftConfig config = AntCraftConfig.get();
		int w = width.orElseGet(() -> fromConfig ? config.stripWidth : DEFAULT_WIDTH);
		int l = length.orElseGet(() -> fromConfig ? config.stripLength : DEFAULT_LENGTH);
		return of(w, l);
	}

	public StripBounds {
		if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("empty strip bounds");
	}

	/** The overworld / Nether footprint: x 0..width-1, z 0..length-1. */
	public static StripBounds of(int width, int length) {
		return new StripBounds(0, width - 1, 0, length - 1);
	}

	public int width() {
		return this.maxX - this.minX + 1;
	}

	public int length() {
		return this.maxZ - this.minZ + 1;
	}

	/** Middle column (for even widths the eastern of the two middle columns; 10 for x 0..19). */
	public int centerX() {
		return this.minX + this.width() / 2;
	}

	public int centerZ() {
		return this.minZ + this.length() / 2;
	}

	public int wallMinX() {
		return this.minX - 1;
	}

	public int wallMaxX() {
		return this.maxX + 1;
	}

	public int wallMinZ() {
		return this.minZ - 1;
	}

	public int wallMaxZ() {
		return this.maxZ + 1;
	}

	public boolean inside(int x, int z) {
		return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
	}

	/** True for the barrier ring around the strip. */
	public boolean isWall(int x, int z) {
		boolean inWallX = x >= this.wallMinX() && x <= this.wallMaxX();
		boolean inWallZ = z >= this.wallMinZ() && z <= this.wallMaxZ();
		return inWallX && inWallZ && !this.inside(x, z);
	}

	public boolean chunkOverlaps(ChunkPos pos) {
		return pos.getMaxBlockX() >= this.minX && pos.getMinBlockX() <= this.maxX && pos.getMaxBlockZ() >= this.minZ && pos.getMinBlockZ() <= this.maxZ;
	}

	/** True if the chunk has any strip or wall column. */
	public boolean chunkTouches(ChunkPos pos) {
		return pos.getMaxBlockX() >= this.wallMinX() && pos.getMinBlockX() <= this.wallMaxX()
			&& pos.getMaxBlockZ() >= this.wallMinZ() && pos.getMinBlockZ() <= this.wallMaxZ();
	}

	/** Clamp a position into the strip, keeping {@code margin} blocks away from the walls. */
	public BlockPos clampInside(BlockPos pos, int margin) {
		int mx = Math.min(margin, (this.width() - 1) / 2), mz = Math.min(margin, (this.length() - 1) / 2);
		int x = Math.clamp(pos.getX(), this.minX + mx, this.maxX - mx);
		int z = Math.clamp(pos.getZ(), this.minZ + mz, this.maxZ - mz);
		return new BlockPos(x, pos.getY(), z);
	}

	public int clampX(int x) {
		return Math.clamp(x, this.minX, this.maxX);
	}

	public int clampZ(int z) {
		return Math.clamp(z, this.minZ, this.maxZ);
	}

	/**
	 * A z position scaled with the strip length: {@code atDefault} is where it sits in the default
	 * 300-long strip (measured from minZ).
	 */
	public int scaledZ(int atDefault) {
		return this.minZ + (int) ((long) atDefault * this.length() / DEFAULT_LENGTH);
	}
}
