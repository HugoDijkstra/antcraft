package dev.antcraft;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The vertical geometry of the strip overworld: where the vanilla world sits, the holed bedrock
 * floors, the deep layers between them, which floors are gated, and what each layer contains
 * ({@link LayerSpec}). Immutable; one instance per layout id, saved with the world in the
 * generator's {@code layout} field.
 *
 * <p>Layout 1 is the original world (vanilla -64..320, 8 floors 256 apart, world -2032..335). Its
 * numbers must never change: existing worlds generate new chunks from them.
 *
 * <pre>
 *  y  335  ─ top of the world (barrier walls go all the way up)
 *  y  ~64  ─ vanilla surface
 *  y  -61  ─ layer 0 (vanilla underground) ends
 *  y  -62..-64      bedrock floor 0 (one hole)
 *  layer 1 ...
 *  y -318..-320     bedrock floor 1 (one hole)
 *  ...
 *  y -1854..-1856   bedrock floor 7 (one hole)
 *  layer 8 (End portal room at the very bottom)
 *  y -2028..-2032   solid bedrock bottom
 * </pre>
 *
 * The geometry methods are pure arithmetic (no Minecraft bootstrap needed); the layer table
 * ({@link #layer(int)}) is built on first use because it holds block states.
 */
public final class StripLayout {
	/** The pyramid is 21 x 21 blocks; its north edge sits this far into its start chunk. */
	public static final int PYRAMID_SIZE = 21, PYRAMID_OFFSET_Z = 4;

	public static final StripLayout LAYOUT_1 = new StripLayout(
		1, 0, -2032, 335, -64, -2028, 8,
		new int[] {0, 256, 256, 256, 256, 256, 256, 256},
		3, 6, 40,
		5, -1280,
		4, 7, -1, -1, -1,
		guardians(Map.of(0, GuardianId.TUNNEL_CAPTAIN, 2, GuardianId.DEEP_BEAST, 6, GuardianId.DESERT_COLOSSUS)),
		6,
		new String[] {null, "plains", "taiga", "plains", "savanna", "plains", "desert", "taiga", "snowy"},
		LayerTables::layout1
	);

	/** Every layout by id (index = id). */
	private static final StripLayout[] BY_ID = {null, LAYOUT_1};

	public static final int LATEST = 1;

	public static StripLayout byId(int id) {
		if (id < 1 || id >= BY_ID.length) throw new IllegalArgumentException("Unknown AntCraft strip layout " + id);
		return BY_ID[id];
	}

	public final int id;
	/** D: how far the vanilla world is moved up (0 = not moved). */
	public final int frameOffset;
	/** World height, must match the dimension type. */
	public final int worldMinY, worldMaxY;
	/** Vanilla terrain starts here (floorBase(0)); everything below is ours. */
	public final int vanillaMinY;
	/** Solid bedrock bottom of the world: worldMinY..bottomTopY. */
	public final int bottomTopY;
	/** Number of holed floors = number of deep layers (layers 1..deepLayers; layer 0 is vanilla). */
	public final int floorCount, deepLayers;
	/** spacing[l]: floor l-1 to floor l, floor base to floor base (index 0 unused). */
	private final int[] spacing;
	private final int[] floorBase;
	/** Holed floors are this thick; the open hall above each floor; the forest's open air. */
	public final int floorThickness, hallHeight, forestHeight;
	/** The deep dark layer with the ancient city, and the city's jigsaw start y. */
	public final int cityLayer, cityStartY;
	/** How far the deep city's anchor (-1280 in its JSON) is moved: cityStartY + 1280. */
	public final int cityAnchorDelta;
	/** Gated floors: Nether Seal, Ancient Seal, Ember Seal (-1 = none). */
	public final int sealedFloor, ancientSealedFloor, emberSealedFloor;
	/** The Trial Vault layer and its start height (-1 = none). */
	public final int vaultLayer, vaultStartY;
	/** Floors whose hall is a closed boss room, ascending; and who guards each. */
	private final int[] guardianFloors;
	private final Map<Integer, GuardianId> guardians;
	/** The underground desert (pyramid). */
	public final int desertLayer;
	/** Village type per layer (index = layer), null = no village. */
	private final String[] villageTypes;
	private final Function<StripLayout, LayerSpec[]> tableFactory;
	private volatile LayerSpec @org.jspecify.annotations.Nullable [] layers;

	private StripLayout(
		int id, int frameOffset, int worldMinY, int worldMaxY, int vanillaMinY, int bottomTopY, int floorCount,
		int[] spacing, int floorThickness, int hallHeight, int forestHeight, int cityLayer, int cityStartY,
		int sealedFloor, int ancientSealedFloor, int emberSealedFloor, int vaultLayer, int vaultStartY,
		Map<Integer, GuardianId> guardians, int desertLayer, String[] villageTypes, Function<StripLayout, LayerSpec[]> tableFactory
	) {
		if (spacing.length != floorCount) throw new IllegalArgumentException("spacing needs floorCount entries (index 0 unused)");
		this.id = id;
		this.frameOffset = frameOffset;
		this.worldMinY = worldMinY;
		this.worldMaxY = worldMaxY;
		this.vanillaMinY = vanillaMinY;
		this.bottomTopY = bottomTopY;
		this.floorCount = floorCount;
		this.deepLayers = floorCount;
		this.spacing = spacing.clone();
		this.floorBase = new int[floorCount];
		int y = vanillaMinY;
		for (int k = 0; k < floorCount; k++) {
			if (k > 0) y -= spacing[k];
			this.floorBase[k] = y;
		}
		this.floorThickness = floorThickness;
		this.hallHeight = hallHeight;
		this.forestHeight = forestHeight;
		this.cityLayer = cityLayer;
		this.cityStartY = cityStartY;
		this.cityAnchorDelta = cityStartY + 1280;
		this.sealedFloor = sealedFloor;
		this.ancientSealedFloor = ancientSealedFloor;
		this.emberSealedFloor = emberSealedFloor;
		this.vaultLayer = vaultLayer;
		this.vaultStartY = vaultStartY;
		this.guardians = guardians;
		this.guardianFloors = guardians.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		this.desertLayer = desertLayer;
		this.villageTypes = villageTypes.clone();
		this.tableFactory = tableFactory;
	}

	private static Map<Integer, GuardianId> guardians(Map<Integer, GuardianId> map) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(map));
	}

	// ------------------------------------------------------------------ layer table

	/** What deep layer {@code layer} contains (1..deepLayers; 0 is a placeholder for the vanilla layer). */
	public LayerSpec layer(int layer) {
		LayerSpec[] table = this.layers;
		if (table == null) {
			synchronized (this) {
				table = this.layers;
				if (table == null) {
					table = this.tableFactory.apply(this);
					if (table.length != this.deepLayers + 1) throw new IllegalStateException("layout " + this.id + " needs " + (this.deepLayers + 1) + " layer rows");
					this.layers = table;
				}
			}
		}
		return table[layer];
	}

	// ------------------------------------------------------------------ floors and layers

	/** Spacing floor l-1 to floor l (l = 1..floorCount-1). */
	public int spacing(int l) {
		return this.spacing[l];
	}

	/** Lowest y of holed floor k: floor k occupies [floorBase(k), floorTop(k)]. */
	public int floorBase(int k) {
		return this.floorBase[k];
	}

	public int floorTop(int k) {
		return this.floorBase(k) + this.floorThickness - 1;
	}

	/** Lowest non-bedrock y of deep layer {@code layer} (1..deepLayers). */
	public int layerBottom(int layer) {
		return layer < this.deepLayers ? this.floorTop(layer) + 1 : this.bottomTopY + 1;
	}

	/** Highest y of deep layer {@code layer} (1..deepLayers). */
	public int layerTop(int layer) {
		return this.floorBase(layer - 1) - 1;
	}

	/** Lowest block of the floor-hall air above the floor that bounds layer {@code layer}'s bottom; 0 = vanilla layer. */
	public int hallBottom(int layer) {
		return layer == 0 ? this.floorTop(0) + 1 : this.layerBottom(layer);
	}

	/** y of the grass surface (before rolling-hill offset) in the forest of a deep layer. */
	public int forestFloor(int layer) {
		int bottom = this.layerBottom(layer);
		int top = this.layerTop(layer);
		return bottom + (int) ((top - bottom) * 0.45);
	}

	/** Layer index for a y: 1..deepLayers, 0 for y >= floorTop(0)+1, -1 for floors/bottom. */
	public int layerAt(int y) {
		if (y > this.floorTop(0)) return 0;
		for (int layer = 1; layer <= this.deepLayers; layer++) {
			if (y <= this.layerTop(layer) && y >= this.layerBottom(layer)) return layer;
		}
		return -1;
	}

	/** Floor index if y is inside a holed bedrock floor, else -1. */
	public int floorAt(int y) {
		for (int k = 0; k < this.floorCount; k++) {
			if (y >= this.floorBase(k) && y <= this.floorTop(k)) return k;
		}
		return -1;
	}

	// ------------------------------------------------------------------ gates

	public boolean isGuardianFloor(int k) {
		return this.guardians.containsKey(k);
	}

	/** Guardian floors, ascending (a copy). */
	public int[] guardianFloors() {
		return this.guardianFloors.clone();
	}

	public int guardianFloorCount() {
		return this.guardianFloors.length;
	}

	public int guardianFloor(int index) {
		return this.guardianFloors[index];
	}

	/** Who guards floor k, or null. */
	public @org.jspecify.annotations.Nullable GuardianId guardian(int k) {
		return this.guardians.get(k);
	}

	public boolean isVault(int layer) {
		return this.vaultLayer >= 1 && layer == this.vaultLayer;
	}

	// ------------------------------------------------------------------ the ancient city

	/** Vertical extent the city may occupy (for clipping its parts outside the strip). */
	public int cityMinY() {
		return this.cityStartY - 40;
	}

	public int cityMaxY() {
		return this.cityStartY + 60;
	}

	/** The ancient city starts in this chunk, in the middle of the strip. */
	public ChunkPos cityChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.centerZ() >> 4);
	}

	// ------------------------------------------------------------------ depth scaling

	/** Depth scaled to layout 1's 8 layers, as a float (exactly {@code layer} in layout 1). */
	public float hardnessScale(int layer) {
		return layer * 8f / this.deepLayers;
	}

	/** Depth scaled to layout 1's 8 layers, rounded (exactly {@code layer} in layout 1). */
	public int scaledLayer(int layer) {
		return this.deepLayers == 8 ? layer : Math.max(1, Math.round(layer * 8f / this.deepLayers));
	}

	// ------------------------------------------------------------------ villages

	/** Village type in a deep layer's forest ("plains", ...), or null. */
	public @org.jspecify.annotations.Nullable String villageType(int layer) {
		return layer >= 0 && layer < this.villageTypes.length ? this.villageTypes[layer] : null;
	}

	/** Structure path of a deep layer's village ({@code antcraft:<path>}), or null. */
	public @org.jspecify.annotations.Nullable String villageStructure(int layer) {
		String type = this.villageType(layer);
		return type == null ? null : "deep_village_" + type;
	}

	/**
	 * Chunk where the village of a deep layer starts: alternating sides, a different chunk row per
	 * layer, never in the first or last chunk row (the village is moved to the strip's centre x later).
	 *
	 * <p>Layer L sits {@code L * step} rows after a seeded first row (wrapping around). The step
	 * grows with the strip length so long strips spread their villages out, and it is coprime with
	 * the number of rows, so all layers get different rows whenever there are enough rows (a strip
	 * of the minimum length 150 has exactly eight). Two layers with the same village type in the
	 * same chunk would otherwise overwrite each other's structure start.
	 */
	public ChunkPos villageChunk(long seed, int layer, StripBounds bounds) {
		int firstRow = bounds.minZ() >> 4, lastRow = bounds.maxZ() >> 4;
		int rows = Math.max(1, lastRow - firstRow - 1); // 17 for the default 300-long strip
		int base = (int) Math.floorMod(Strip.mix(seed, 7000), (long) rows);
		int chunkX = layer % 2 == 0 ? bounds.minX() >> 4 : bounds.maxX() >> 4;
		return new ChunkPos(chunkX, firstRow + 1 + Math.floorMod(base + layer * this.villageStep(rows), rows));
	}

	/** Row step between consecutive layers' villages: about rows / deepLayers (2 for 17 rows in layout 1), coprime with rows. */
	int villageStep(int rows) {
		if (rows <= 2) return 1;
		int step = Math.max(2, rows / this.deepLayers);
		while (gcd(step, rows) != 1) step++;
		return step;
	}

	private static int gcd(int a, int b) {
		while (b != 0) {
			int t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	// ------------------------------------------------------------------ underground desert pyramid

	/**
	 * Chunk where the desert pyramid of the underground desert starts: in the middle of the
	 * strip's width, in a seeded chunk row at least six rows (96 blocks) from the layer's village if
	 * the strip is long enough (else as far as it can be), preferably not under the hole of the
	 * floor above, and always with the whole pyramid inside the strip's length.
	 */
	public ChunkPos pyramidChunk(long seed, StripBounds bounds) {
		int firstRow = (bounds.minZ() + 1 - PYRAMID_OFFSET_Z + 15) >> 4; // north edge at least 1 block from the wall
		int lastRow = (bounds.maxZ() - 1 - PYRAMID_OFFSET_Z - (PYRAMID_SIZE - 1)) >> 4;
		if (lastRow < firstRow) lastRow = firstRow;
		int rows = lastRow - firstRow + 1;
		int villageRow = this.villageChunk(seed, this.desertLayer, bounds).z();
		int holeZ = this.holeZ(seed, this.desertLayer - 1, bounds);
		int startIndex = (int) Math.floorMod(Strip.mix(seed, 8000), (long) rows);
		int best = firstRow, bestScore = -1;
		for (int i = 0; i < rows; i++) {
			int row = firstRow + (startIndex + i) % rows;
			int north = (row << 4) + PYRAMID_OFFSET_Z;
			boolean underHole = holeZ >= north - 6 && holeZ <= north + PYRAMID_SIZE + 5;
			int score = Math.min(Math.abs(row - villageRow), 6) * 2 + (underHole ? 0 : 1);
			if (score > bestScore) {
				bestScore = score;
				best = row;
			}
		}
		return new ChunkPos(bounds.centerX() >> 4, best);
	}

	// ------------------------------------------------------------------ holes and the portal room

	/** Hole of floor k: at least 2 blocks from the walls (x in [2, 17], z in [2, 297] for 20 x 300). */
	public int holeX(long seed, int k, StripBounds bounds) {
		return bounds.minX() + 2 + (int) Math.floorMod(Strip.mix(seed, 1000 + k), (long) (bounds.width() - 4));
	}

	public int holeZ(long seed, int k, StripBounds bounds) {
		int range = bounds.length() - 4;
		int z = bounds.minZ() + 2 + (int) Math.floorMod(Strip.mix(seed, 2000 + k), (long) range);
		if (k == this.floorCount - 1) {
			// the last hole must not come down on top of the End portal room
			int room = this.portalRoomZ(seed, bounds);
			while (Math.abs(z - room) <= PortalRoom.HALF_LENGTH + 3) {
				z = bounds.minZ() + 2 + Math.floorMod(z - bounds.minZ() - 2 + 2 * PortalRoom.HALF_LENGTH + 7, range);
			}
		}
		return z;
	}

	/** Centre Z of the End portal room in the bottom layer: at least 30 blocks from either end. */
	public int portalRoomZ(long seed, StripBounds bounds) {
		return bounds.minZ() + 30 + (int) Math.floorMod(Strip.mix(seed, 3000), (long) (bounds.length() - 59));
	}

	public int portalRoomFloorY() {
		return this.layerBottom(this.deepLayers); // stone-brick floor sits right on the bottom bedrock
	}

	/** Where eyes of ender should fly: the middle of the portal. */
	public BlockPos portalTarget(long seed, StripBounds bounds) {
		return new BlockPos(PortalRoom.portalX(bounds), this.portalRoomFloorY() + 2, this.portalRoomZ(seed, bounds));
	}

	@Override
	public String toString() {
		return "StripLayout[" + this.id + ", floors " + Arrays.toString(this.floorBase) + "]";
	}
}
