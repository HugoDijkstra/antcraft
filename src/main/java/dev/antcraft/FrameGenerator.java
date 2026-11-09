package dev.antcraft;

/**
 * A strip generator that may run the vanilla generator in a vertically shifted "frame": vanilla
 * terrain is generated as usual and lands {@link #frameOffset()} blocks higher in the real world.
 * The offset is 0 in every layout that does not move the vanilla world.
 */
public interface FrameGenerator {
	/** D: how far the vanilla world is moved up (0 = not moved). */
	int frameOffset();

	/** Lowest real y of the vanilla frame: the vanilla generator's min y + D. */
	int frameMinY();

	/** Height of the vanilla frame (the vanilla generator's gen depth). */
	int frameHeight();

	default boolean framed() {
		return this.frameOffset() != 0;
	}
}
