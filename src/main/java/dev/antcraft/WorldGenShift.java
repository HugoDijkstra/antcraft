package dev.antcraft;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * How far absolute world-generation heights (vertical anchors, a few hard-coded structure
 * constants) are moved in a framed strip world, and the per-thread exceptions to it.
 *
 * <p>The ThreadLocals are only set on the structure-start or feature thread, always in
 * try/finally, and never across {@code buildTerrain} (which runs on another executor; the vanilla
 * noise generator inside the frame sees a plain {@code NoiseBasedChunkGenerator} and no shift).
 */
public final class WorldGenShift {
	private WorldGenShift() {}

	/** Replaces the frame offset as the anchor shift while one of our own deep starts is laid out. */
	private static final ThreadLocal<Integer> OVERRIDE = new ThreadLocal<>();
	/** Depth counter: > 0 while a projected jigsaw start samples its start height (no shift). */
	private static final ThreadLocal<int[]> SUPPRESS = ThreadLocal.withInitial(() -> new int[1]);
	/** How far the ocean monument building piece is moved from its vanilla y 39 (null = 0). */
	private static final ThreadLocal<Integer> MONUMENT_DY = new ThreadLocal<>();

	/**
	 * Anchor shift for a world-generation context of this generator: the override while one is
	 * set, else the generator's frame offset. 0 for every generator that is not a strip generator.
	 */
	public static int anchorShift(@Nullable ChunkGenerator generator) {
		if (!(generator instanceof FrameGenerator frame)) return 0;
		Integer override = OVERRIDE.get();
		return override != null ? override : frame.frameOffset();
	}

	public static boolean suppressed() {
		return SUPPRESS.get()[0] > 0;
	}

	public static void pushSuppress() {
		SUPPRESS.get()[0]++;
	}

	public static void popSuppress() {
		SUPPRESS.get()[0]--;
	}

	/** Runs {@code body} with the anchor shift overridden to {@code shift} on this thread. */
	public static <T> T withOverride(int shift, java.util.function.Supplier<T> body) {
		Integer previous = OVERRIDE.get();
		OVERRIDE.set(shift);
		try {
			return body.get();
		} finally {
			if (previous == null) OVERRIDE.remove();
			else OVERRIDE.set(previous);
		}
	}

	/**
	 * The Trial Vault's start (spec 3.5.2): the jigsaw keeps its free space inside the strip around
	 * the start piece; {@code rotation} (if set) replaces the start piece's random rotation.
	 */
	public record VaultContext(StripBounds bounds, int vaultZ, int margin, int maxY, net.minecraft.world.level.block.@org.jspecify.annotations.Nullable Rotation rotation, int depth) {}

	private static final ThreadLocal<VaultContext> VAULT = new ThreadLocal<>();

	public static @Nullable VaultContext vault() {
		return VAULT.get();
	}

	public static <T> T withVault(VaultContext context, java.util.function.Supplier<T> body) {
		VaultContext previous = VAULT.get();
		VAULT.set(context);
		try {
			return body.get();
		} finally {
			if (previous == null) VAULT.remove();
			else VAULT.set(previous);
		}
	}

	public static int monumentDy() {
		Integer dy = MONUMENT_DY.get();
		return dy == null ? 0 : dy;
	}

	public static void setMonumentDy(int dy) {
		MONUMENT_DY.set(dy);
	}

	public static void clearMonumentDy() {
		MONUMENT_DY.remove();
	}
}
