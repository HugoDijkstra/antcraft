package dev.antcraft;

import org.jspecify.annotations.Nullable;

/**
 * The geometry {@link ArenaManager} needs from a layout with boss rooms: the strip overworld
 * ({@link StripLayout}) and the tall Nether ({@link NetherLayout}). A boss room is the hall above a
 * guardian floor, around that floor's hole.
 */
public interface ArenaLayout {
	/** Layout id for the guardians' strength (overworld layout id; the tall Nether counts as a later layout). */
	int arenaLayoutId();

	int floorTop(int k);

	int hallHeight();

	int holeX(long seed, int k, StripBounds bounds);

	int holeZ(long seed, int k, StripBounds bounds);

	/** Guardian floors, ascending (a copy). */
	int[] guardianFloors();

	@Nullable GuardianId guardian(int k);
}
