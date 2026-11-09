package dev.antcraft;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A deep structure piece's box with what the terrain does around it (tall layouts; layout 1 only
 * uses plain boxes). {@link Kind#FOREST} pieces flatten the forest floor, {@link Kind#BURIED} ones
 * also keep solid layer stone around them (trail ruins, the igloo basement: no Beardifier down
 * there), {@link Kind#CITY} is the ancient city.
 */
public final class DeepBox extends BoundingBox {
	public enum Kind { FOREST, BURIED, CITY }

	public final Kind kind;

	public DeepBox(BoundingBox box, Kind kind) {
		super(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		this.kind = kind;
	}

	public static boolean isBuried(BoundingBox box) {
		return box instanceof DeepBox deep && deep.kind == Kind.BURIED;
	}
}
