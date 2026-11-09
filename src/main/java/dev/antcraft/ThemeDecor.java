package dev.antcraft;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.AquaticFeatures;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.data.worldgen.features.NetherFeatures;
import net.minecraft.data.worldgen.features.VegetationFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The tall layout's per-theme decoration (spec v2 3.2, 3.9): vanilla features placed on the
 * forest floor, the forest ceiling, the sea bed or in the caves of each themed layer, a few
 * per-column touches (snow, powder snow, shriekers), and the monster rooms. Runs after the trees
 * and ground plants; layout 1 never gets here.
 */
public final class ThemeDecor {
	private ThemeDecor() {}

	private enum Mode { GROUND, CEILING, SEABED, POND, CAVE }

	private record Placement(ResourceKey<Feature> feature, double perChunk, Mode mode) {}

	private static Placement p(ResourceKey<Feature> feature, double perChunk, Mode mode) {
		return new Placement(feature, perChunk, mode);
	}

	private static List<Placement> placements(LayerTheme theme) {
		return switch (theme) {
			case TAIGA_DEEP -> List.of(p(MiscOverworldFeatures.FOREST_ROCK, 0.5, Mode.GROUND));
			case AUTUMN_HOLLOW -> List.of(p(VegetationFeatures.LEAF_LITTER, 3, Mode.GROUND), p(VegetationFeatures.WILDFLOWER, 1, Mode.GROUND));
			case OCHRE_BADLANDS -> List.of(p(VegetationFeatures.DEAD_BUSH, 1, Mode.GROUND), p(VegetationFeatures.DRY_GRASS, 2, Mode.GROUND));
			case LUSH_GROTTO -> List.of(p(CaveFeatures.DRIPLEAF, 3, Mode.GROUND), p(CaveFeatures.SPORE_BLOSSOM, 1.5, Mode.CEILING), p(CaveFeatures.MOSS_PATCH_CEILING, 1, Mode.CEILING));
			case JUNGLE -> List.of(p(VegetationFeatures.BAMBOO_NO_PODZOL, 0.4, Mode.GROUND));
			// the springs go straight on the forest floor: the rooted variant searches up to 184 blocks for a spot and
			// its tuff cover replaces any solid block, the bedrock floor above included; no sulfur lakes (they cut walls)
			case SULFUR_SPRINGS -> List.of(p(CaveFeatures.SULFUR_SPIKE_CLUSTER, 1, Mode.CAVE), p(MiscOverworldFeatures.SULFUR_SPRING, 0.25, Mode.GROUND));
			case PALE_HOLLOW -> List.of(p(VegetationFeatures.PALE_MOSS_PATCH, 1, Mode.GROUND), p(VegetationFeatures.PALE_FOREST_FLOWER, 0.5, Mode.GROUND));
			case DEEP_DARK -> List.of(p(CaveFeatures.SCULK_PATCH_DEEP_DARK, 2, Mode.GROUND), p(CaveFeatures.SCULK_VEIN, 2, Mode.CAVE));
			case CRYSTAL_HOLLOWS -> List.of(p(CaveFeatures.AMETHYST_GEODE, 0.5, Mode.CAVE));
			case SUNKEN_REEF -> List.of(p(AquaticFeatures.WARM_OCEAN_VEGETATION, 3, Mode.SEABED), p(AquaticFeatures.KELP, 2, Mode.SEABED),
				p(AquaticFeatures.SEAGRASS_MID, 4, Mode.SEABED), p(AquaticFeatures.SEA_PICKLE, 0.6, Mode.SEABED));
			case MANGROVE -> List.of(p(VegetationFeatures.WATERLILY, 1.5, Mode.POND));
			case FROZEN_DEPTHS -> List.of(p(MiscOverworldFeatures.ICE_SPIKE, 0.8, Mode.GROUND), p(MiscOverworldFeatures.BLUE_ICE, 0.5, Mode.CAVE));
			case EMBER_RIFT -> List.of(p(NetherFeatures.SMALL_BASALT_COLUMNS, 0.4, Mode.GROUND), p(NetherFeatures.TWISTING_VINES, 1, Mode.GROUND));
			default -> List.of();
		};
	}

	/** Theme features of every forest layer in this chunk. */
	public static void decorate(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> deepPieces,
		@Nullable VaultPlan vault) {
		ChunkPos pos = chunk.getPos();
		StripBounds bounds = terrain.bounds();
		StripLayout layout = terrain.layout();
		int minX = Math.max(pos.getMinBlockX(), bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		var features = level.registryAccess().lookupOrThrow(Registries.FEATURE);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			LayerSpec spec = layout.layer(layer);
			if (!spec.hasForest() || spec.theme() == null) continue;
			RandomSource random = new XoroshiroRandomSource(Strip.mix(terrain.seed(), 9300 + layer), Strip.mix(pos.x(), pos.z()));
			for (Placement placement : placements(spec.theme())) {
				Holder<Feature> feature = features.get(placement.feature()).orElse(null);
				double expected = placement.perChunk() * areaScale;
				int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
				for (int i = 0; i < count; i++) {
					// cave features stay away from the walls (they grow several blocks)
					int margin = placement.mode() == Mode.CAVE ? 4 : 1;
					int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), bounds.minX() + margin, bounds.maxX() - margin);
					int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), bounds.minZ() + margin, bounds.maxZ() - margin);
					BlockPos origin = origin(level, terrain, layer, x, z, placement.mode(), random, at);
					if (origin == null || feature == null) continue;
					if (terrain.nearHoleAbove(layer, x, z, 4, vault) || UndergroundForest.inVillage(layout, deepPieces, layer, x, z, 4)) continue;
					feature.value().place(level, generator, random, origin);
				}
			}
			columns(level, terrain, layer, spec.theme(), minX, maxX, minZ, maxZ, deepPieces, vault, at);
		}
	}

	private static @Nullable BlockPos origin(WorldGenLevel level, DeepTerrain terrain, int layer, int x, int z, Mode mode, RandomSource random, BlockPos.MutableBlockPos at) {
		StripLayout layout = terrain.layout();
		int surface = terrain.surfaceAt(layer, x, z);
		switch (mode) {
			case GROUND -> {
				BlockState ground = level.getBlockState(at.set(x, surface, z));
				BlockState above = level.getBlockState(at.set(x, surface + 1, z));
				if (ground.isAir() || !ground.getFluidState().isEmpty() || !above.isAir()) return null;
				return new BlockPos(x, surface + 1, z);
			}
			case SEABED -> {
				if (!level.getFluidState(at.set(x, surface + 1, z)).is(net.minecraft.tags.FluidTags.WATER)) return null;
				return new BlockPos(x, surface + 1, z);
			}
			case POND -> {
				if (!terrain.isPond(layer, x, z) || !level.getFluidState(at.set(x, surface, z)).isSource() || !level.getBlockState(at.set(x, surface + 1, z)).isAir()) return null;
				return new BlockPos(x, surface + 1, z);
			}
			case CEILING -> {
				int ceil = terrain.forestCeiling(layer, x, z);
				if (!level.getBlockState(at.set(x, ceil - 1, z)).isAir() || level.getBlockState(at.set(x, ceil, z)).isAir()) return null;
				return new BlockPos(x, ceil - 1, z);
			}
			case CAVE -> {
				// a cave floor in the band below the forest (or above its ceiling)
				int bottom = layout.layerBottom(layer) + layout.hallHeight + 6, top = surface - 12;
				if (random.nextBoolean()) {
					bottom = terrain.forestCeiling(layer, x, z) + 10;
					top = layout.layerTop(layer) - 8;
				}
				if (top <= bottom) return null;
				int y = bottom + random.nextInt(top - bottom + 1);
				for (int i = 0; i < 16 && y > bottom; i++, y--) {
					if (level.getBlockState(at.set(x, y, z)).isAir() && level.getBlockState(at.set(x, y - 1, z)).isSolid()) return new BlockPos(x, y, z);
				}
				return null;
			}
		}
		return null;
	}

	/** Per-column touches: snow layers and powder snow in the Frozen Depths, rare shriekers in the Deep Dark. */
	private static void columns(WorldGenLevel level, DeepTerrain terrain, int layer, LayerTheme theme, int minX, int maxX, int minZ, int maxZ,
		List<BoundingBox> deepPieces, @Nullable VaultPlan vault, BlockPos.MutableBlockPos at) {
		if (theme != LayerTheme.FROZEN_DEPTHS && theme != LayerTheme.DEEP_DARK) return;
		StripLayout layout = terrain.layout();
		StripBounds bounds = terrain.bounds();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int surface = terrain.surfaceAt(layer, x, z);
				double r = terrain.hash01(x, surface, z, 9400 + layer);
				// never near a hole's fall line or a structure (powder snow and shriekers hurt); shriekers
				// keep 12 from the holes and the city key chest (spec 3.2)
				int holeRadius = theme == LayerTheme.DEEP_DARK ? SHRIEKER_HOLE_RADIUS : 6;
				boolean safe = !terrain.nearHoleAbove(layer, x, z, holeRadius, vault) && !UndergroundForest.inVillage(layout, deepPieces, layer, x, z, 6)
					&& !nearHoleBelow(terrain, layer, x, z, holeRadius, vault) && !(theme == LayerTheme.DEEP_DARK && nearKeyChest(layout, bounds, x, surface, z));
				if (theme == LayerTheme.FROZEN_DEPTHS) {
					if (!level.getBlockState(at.set(x, surface, z)).is(Blocks.SNOW_BLOCK)) continue;
					if (safe && r < 0.015) {
						level.setBlock(at, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
					} else if (level.getBlockState(at.set(x, surface + 1, z)).isAir()) {
						level.setBlock(at, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + (int) (r * 2)), Block.UPDATE_CLIENTS);
					}
				} else if (safe && r < 0.003 && level.getBlockState(at.set(x, surface, z)).is(Blocks.SCULK) && level.getBlockState(at.set(x, surface + 1, z)).isAir()) {
					level.setBlock(at, Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, true), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/** How far (square) shriekers keep from the hole columns over and under their layer. */
	static final int SHRIEKER_HOLE_RADIUS = 12;

	/**
	 * Within 12 of where the city key chest can be: it is placed at decoration time on a city floor
	 * in the city's start chunk, so the whole chunk (inside the strip) and the city's height count.
	 */
	static boolean nearKeyChest(StripLayout layout, StripBounds bounds, int x, int y, int z) {
		if (layout.cityLayer < 1) return false;
		ChunkPos city = layout.cityChunk(bounds);
		int r = SHRIEKER_HOLE_RADIUS;
		return x >= Math.max(city.getMinBlockX(), bounds.minX()) - r && x <= Math.min(city.getMaxBlockX(), bounds.maxX()) + r
			&& z >= Math.max(city.getMinBlockZ(), bounds.minZ()) - r && z <= Math.min(city.getMaxBlockZ(), bounds.maxZ()) + r
			&& y >= layout.cityMinY() - r && y <= layout.cityMaxY() + r;
	}

	/**
	 * After a chunk's decoration: shriekers that features grew in its 3x3 region (the deep-dark
	 * sculk patches grow summoning shriekers wherever their charge runs, not only at the patch
	 * origin) are removed where spec 3.2 forbids them: within 12 of a hole column over or under the
	 * layer, near the city key chest, or within 6 of a deep piece. The ancient city's own shriekers
	 * stay, except that those near the key chest no longer summon wardens. A pure function of
	 * positions, so the result does not depend on chunk order.
	 */
	public static void cleanShriekers(WorldGenLevel level, DeepTerrain terrain, ChunkPos center, List<BoundingBox> pieces, @Nullable VaultPlan vault) {
		StripLayout layout = terrain.layout();
		StripBounds bounds = terrain.bounds();
		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			if (layout.layer(layer).theme() != LayerTheme.DEEP_DARK) continue;
			int y0 = layout.layerBottom(layer), y1 = layout.layerTop(layer);
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			for (int cx = center.x() - 1; cx <= center.x() + 1; cx++) {
				for (int cz = center.z() - 1; cz <= center.z() + 1; cz++) {
					ChunkAccess chunk = level.getChunk(cx, cz);
					var sections = chunk.getSections();
					for (int i = 0; i < sections.length; i++) {
						int sy = chunk.getMinY() + i * 16;
						if (sy + 15 < y0 || sy > y1 || !sections[i].maybeHas(state -> state.is(Blocks.SCULK_SHRIEKER))) continue;
						for (int lx = 0; lx < 16; lx++) {
							for (int lz = 0; lz < 16; lz++) {
								for (int ly = 0; ly < 16; ly++) {
									BlockState state = sections[i].getBlockState(lx, ly, lz);
									if (!state.is(Blocks.SCULK_SHRIEKER)) continue;
									int x = (cx << 4) + lx, y = sy + ly, z = (cz << 4) + lz;
									if (y < y0 || y > y1 || !bounds.inside(x, z)) continue;
									boolean chest = nearKeyChest(layout, bounds, x, y, z);
									if (inCity(pieces, x, y, z)) {
										if (chest && state.getValue(SculkShriekerBlock.CAN_SUMMON)) {
											level.setBlock(at.set(x, y, z), state.setValue(SculkShriekerBlock.CAN_SUMMON, false), Block.UPDATE_CLIENTS);
										}
										continue;
									}
									boolean bad = chest || terrain.nearHoleAbove(layer, x, z, SHRIEKER_HOLE_RADIUS, vault) || nearHoleBelow(terrain, layer, x, z, SHRIEKER_HOLE_RADIUS, vault);
									for (BoundingBox box : pieces) bad |= box.inflatedBy(6).isInside(x, y, z);
									if (bad) level.setBlock(at.set(x, y, z), state.getFluidState().isEmpty() ? DeepTerrain.AIR : Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
								}
							}
						}
					}
				}
			}
		}
	}

	private static boolean inCity(List<BoundingBox> pieces, int x, int y, int z) {
		for (BoundingBox box : pieces) if (box instanceof DeepBox deep && deep.kind == DeepBox.Kind.CITY && box.isInside(x, y, z)) return true;
		return false;
	}

	private static boolean nearHoleBelow(DeepTerrain terrain, int layer, int x, int z, int radius, @Nullable VaultPlan vault) {
		return layer < terrain.layout().deepLayers && terrain.nearHoleAbove(layer + 1, x, z, radius, vault);
	}

	// ------------------------------------------------------------------ monster rooms (spec 3.9)

	private static final MonsterRoomFeature MONSTER_ROOM = new MonsterRoomFeature();
	/** Origins tested per chunk and layer at most (a cheap test before the vanilla one). */
	private static final int ROOM_CANDIDATES = 12000;
	/** A room's origin stays this far inside its chunk, so the room (radius at most 4) never reaches a neighbour chunk. */
	private static final int CHUNK_INSET = 4;
	/** Strip columns of the default 20x300 strip: {@link LayerSpec#dungeonRate()} is per that many columns. */
	private static final double DEFAULT_COLUMNS = 20 * 300;

	/**
	 * Cheap version of the monster room's own test, for its smallest size: solid floor and
	 * ceiling at the corners and the centre, and 1..5 openings (two free blocks) in the ring.
	 */
	private static boolean roomFits(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
		for (int dx = -3; dx <= 3; dx += 3) {
			for (int dz = -3; dz <= 3; dz += 3) {
				if (!level.getBlockState(at.set(x + dx, y - 1, z + dz)).isSolid() || !level.getBlockState(at.set(x + dx, y + 4, z + dz)).isSolid()) return false;
			}
		}
		int holes = 0;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) continue; // the ring only
				if (level.getBlockState(at.set(x + dx, y, z + dz)).isAir() && level.getBlockState(at.set(x + dx, y + 1, z + dz)).isAir() && ++holes > 5) return false;
			}
		}
		return holes >= 1;
	}

	/** The x (or z) range a room origin may use in a chunk: 5 from the strip's walls (ends) and {@link #CHUNK_INSET} inside the chunk. */
	private static int[] originRange(int chunkMin, int stripMin, int stripMax) {
		return new int[] {Math.max(chunkMin + CHUNK_INSET, stripMin + 5), Math.min(chunkMin + 15 - CHUNK_INSET, stripMax - 5)};
	}

	/** Strip chunks that can hold a monster room origin, in a fixed order. */
	static List<ChunkPos> roomChunks(StripBounds bounds) {
		List<ChunkPos> chunks = new java.util.ArrayList<>();
		for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) {
			for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++) {
				int[] xr = originRange(cx << 4, bounds.minX(), bounds.maxX()), zr = originRange(cz << 4, bounds.minZ(), bounds.maxZ());
				if (xr[0] <= xr[1] && zr[0] <= zr[1]) chunks.add(new ChunkPos(cx, cz));
			}
		}
		return chunks;
	}

	/**
	 * Monster rooms planned for a chunk in a layer: the layer's count for the whole strip
	 * (dungeonRate per 20x300 strip, scaled by the strip's area) dealt out over the strip's room
	 * chunks in a seeded order. A pure function of seed, bounds and layer, so the counts per layer
	 * do not depend on luck or chunk order.
	 */
	static int plannedRooms(long seed, StripBounds bounds, LayerSpec spec, int layer, ChunkPos pos, List<ChunkPos> chunks) {
		if (spec.dungeonRate() <= 0 || chunks.isEmpty()) return 0;
		int total = (int) Math.round(spec.dungeonRate() * (double) bounds.width() * bounds.length() / DEFAULT_COLUMNS);
		int index = chunks.indexOf(pos);
		if (index < 0 || total <= 0) return 0;
		// a seeded rotation of the chunk list, then a stride through it, so rooms spread along the strip
		int n = chunks.size();
		int offset = (int) Math.floorMod(Strip.mix(seed, 9250 + layer), (long) n);
		int rank = Math.floorMod(index - offset, n);
		int rooms = 0;
		for (int i = 0; i < total; i++) {
			// room i goes to rank floor(i * n / total) (evenly spread), wrapping when there are more rooms than chunks
			int r = (int) ((long) (i % n) * n / Math.min(total, n));
			if (r == rank) rooms++;
		}
		return rooms;
	}

	/**
	 * Vanilla monster rooms in the cave bands, more the deeper the layer (spec 3.9; a planned
	 * number per layer, see {@link #plannedRooms}). A room stays inside its chunk, 5 from the
	 * walls and the strip ends, 6 from hole columns, 5 from every deep piece (and the End portal
	 * room), and never overlaps another room.
	 */
	public static int dungeons(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> avoid, @Nullable VaultPlan vault) {
		ChunkPos pos = chunk.getPos();
		StripBounds bounds = terrain.bounds();
		StripLayout layout = terrain.layout();
		int[] xr = originRange(pos.getMinBlockX(), bounds.minX(), bounds.maxX()), zr = originRange(pos.getMinBlockZ(), bounds.minZ(), bounds.maxZ());
		if (xr[0] > xr[1] || zr[0] > zr[1]) return 0;
		List<ChunkPos> chunks = roomChunks(bounds);
		long seed = terrain.seed();
		int placed = 0;
		BoundingBox portalRoom = PortalRoom.box(seed, bounds, layout).inflatedBy(5);
		List<BoundingBox> rooms = new java.util.ArrayList<>();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			LayerSpec spec = layout.layer(layer);
			if (spec.theme() == null) continue;
			int wanted = plannedRooms(seed, bounds, spec, layer, pos, chunks);
			if (wanted == 0) continue;
			RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 9200 + layer), Strip.mix(pos.x(), pos.z()));
			int ff = layout.forestFloor(layer), b = layout.layerBottom(layer), top = layout.layerTop(layer);
			int[] lower = {b + 10, ff - 12}, upper = {ff + 50, top - 8};
			int made = 0, cheap = 0, vanillaFail = 0, tested = 0;
			// every origin of both bands, in a seeded cyclic order (random spots found too few: rooms only fit
			// in rock right beside a low passage), until the planned rooms are placed
			int[][] bands = random.nextBoolean() ? new int[][] {lower, upper} : new int[][] {upper, lower};
			int w = xr[1] - xr[0] + 1, d = zr[1] - zr[0] + 1;
			int ox = random.nextInt(w), oz = random.nextInt(d), sx = stride(w, 3), sz = stride(d, 5);
			search:
			for (int[] band : bands) {
				int h = band[1] - band[0] + 1;
				if (h <= 0) continue;
				int oy = random.nextInt(h);
				for (int iy = 0; iy < h; iy++) {
					int y = band[0] + (oy + iy) % h;
					for (int ix = 0; ix < w; ix++) {
						int x = xr[0] + (ox + ix * sx) % w; // a stride, so neighbouring spots are not tried in a row
						for (int iz = 0; iz < d; iz++) {
							if (made >= wanted || tested >= ROOM_CANDIDATES) break search;
							int z = zr[0] + (oz + iz * sz) % d;
							if (terrain.nearHoleAbove(layer, x, z, 6, vault) || nearHoleBelow(terrain, layer, x, z, 6, vault)) continue;
							BoundingBox room = new BoundingBox(x - 4, y - 1, z - 4, x + 4, y + 4, z + 4);
							boolean clash = room.intersects(portalRoom);
							for (BoundingBox box : avoid) clash |= room.intersects(box.inflatedBy(5));
							for (BoundingBox other : rooms) clash |= room.intersects(other.inflatedBy(1));
							if (clash) continue;
							// the sea's sealed bed: nothing within 4 blocks under it
							if (spec.theme().isWater() && y + 4 >= terrain.surfaceAt(layer, x, z) - 4) continue;
							tested++;
							if (!roomFits(level, at, x, y, z)) continue;
							cheap++;
							BlockPos origin = new BlockPos(x, y, z);
							if (!MONSTER_ROOM.place(level, generator, random, origin)) {
								vanillaFail++;
								continue;
							}
							rooms.add(room);
							made++;
							placed++;
							if (random.nextBoolean() && level.getBlockEntity(origin) instanceof SpawnerBlockEntity spawner) {
								EntityType<?> mob = themeMob(spec.theme());
								if (mob != null) spawner.setEntityId(mob, random);
							}
						}
					}
				}
			}
			if (made < wanted && StructureAudit.enabled()) {
				AntCraft.LOGGER.info("AntCraft: {} of {} monster rooms fit in layer {} chunk {} ({} spots passed the quick test, {} failed the vanilla one)", made, wanted, layer, pos, cheap, vanillaFail);
			}
		}
		return placed;
	}

	/** The smallest step from {@code preferred} up that visits all of 0..n-1 (coprime with n). */
	private static int stride(int n, int preferred) {
		for (int s = preferred; ; s++) if (java.math.BigInteger.valueOf(s).gcd(java.math.BigInteger.valueOf(n)).intValue() == 1) return s;
	}

	private static @Nullable EntityType<?> themeMob(LayerTheme theme) {
		return switch (theme) {
			case FROZEN_DEPTHS -> EntityTypes.STRAY;
			case BURIED_DESERT -> EntityTypes.HUSK;
			case MANGROVE -> EntityTypes.BOGGED;
			case JUNGLE -> EntityTypes.CAVE_SPIDER;
			case SUNKEN_REEF -> EntityTypes.DROWNED;
			case EMBER_RIFT -> EntityTypes.MAGMA_CUBE;
			default -> EntityTypes.SKELETON;
		};
	}
}
