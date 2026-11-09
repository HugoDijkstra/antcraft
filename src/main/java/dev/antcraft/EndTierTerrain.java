package dev.antcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

/**
 * The tall End's tiers (End layout 2, spec v2 5.2 / 5.3): the Ascent's island tiers E1..E6 and
 * the Crown above the vanilla End, the Underside's tiers U1..U4 below it, all inside the tier zone
 * z -1400..-300 of the strip, plus the hand-built places (gate platforms, the Void Stalker's and
 * the Phantom Matriarch's arenas, the shulker-box vault, the Purpur Crypt and the Crown).
 *
 * <p>Terrain and builds are written raw into the chunk's sections at the noise step ({@link
 * #fill}); every block is a pure function of the seed, the strip and its position, so chunk order
 * does not matter. {@link #decorate} adds what needs a block entity or an entity (gateways with
 * their exits, chests, shulker boxes, shulkers) and the tier features (chorus, end-rod groves),
 * after the vanilla decoration of the chunk.
 */
public final class EndTierTerrain {
	private record Key(long seed, StripBounds bounds, EndLayout layout) {}

	private static final Map<Key, EndTierTerrain> CACHE = new ConcurrentHashMap<>();

	public static EndTierTerrain forSeed(long seed, StripBounds bounds, EndLayout layout) {
		return CACHE.computeIfAbsent(new Key(seed, bounds, layout), k -> new EndTierTerrain(k.seed(), k.bounds(), k.layout()));
	}

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();
	static final BlockState END_STONE_BRICKS = Blocks.END_STONE_BRICKS.defaultBlockState();
	static final BlockState PURPUR = Blocks.PURPUR_BLOCK.defaultBlockState();
	static final BlockState PURPUR_PILLAR = Blocks.PURPUR_PILLAR.defaultBlockState();
	static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
	static final BlockState CRYING_OBSIDIAN = Blocks.CRYING_OBSIDIAN.defaultBlockState();
	static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	static final BlockState GATEWAY = Blocks.END_GATEWAY.defaultBlockState();

	static BlockState rod(Direction facing) {
		return Blocks.END_ROD.defaultBlockState().setValue(DirectionalBlock.FACING, facing);
	}

	/** A block of a hand-built place; null leaves the block as it is. */
	interface Shape {
		@Nullable BlockState at(int x, int y, int z);
	}

	/**
	 * A block entity to fill at decoration: a gateway's exact exit, a chest's items or a
	 * container's loot table.
	 */
	record Fill(BlockPos pos, @Nullable BlockPos gatewayExit, @Nullable List<ItemStack> items, @Nullable ResourceKey<LootTable> loot) {}

	record Build(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Shape shape, List<Fill> fills) {
		boolean intersects(int x0, int z0, int x1, int z1) {
			return this.maxX >= x0 && this.minX <= x1 && this.maxZ >= z0 && this.minZ <= z1;
		}

		boolean contains(int x, int y, int z) {
			return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
		}
	}

	/** Guaranteed flat land of a tier around a platform, a city or the vault (centre on the strip's middle). */
	private record Pad(int z, int radius, int top) {}

	private final long seed;
	private final StripBounds bounds;
	private final EndLayout layout;
	private final List<EndLayout.Tier> tiers;
	private final SimplexNoise[] main, detail, hill, spike;
	private final List<List<Pad>> pads = new ArrayList<>();
	private final List<Build> builds = new ArrayList<>();
	private final int cryptMinZ, cryptMaxZ;

	private EndTierTerrain(long seed, StripBounds bounds, EndLayout layout) {
		this.seed = seed;
		this.bounds = bounds;
		this.layout = layout;
		this.tiers = layout.tiers;
		int n = this.tiers.size();
		this.main = new SimplexNoise[n];
		this.detail = new SimplexNoise[n];
		this.hill = new SimplexNoise[n];
		this.spike = new SimplexNoise[n];
		for (int i = 0; i < n; i++) {
			RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 17000 + i));
			this.main[i] = new SimplexNoise(random);
			this.detail[i] = new SimplexNoise(random);
			this.hill[i] = new SimplexNoise(random);
			this.spike[i] = new SimplexNoise(random);
		}
		EndLayout.Tier crypt = layout.tier("u2");
		this.cryptMinZ = crypt == null ? 0 : Math.min(crypt.startZ(), crypt.exitZ()) - 6;
		this.cryptMaxZ = crypt == null ? -1 : Math.max(crypt.startZ(), crypt.exitZ()) + 6;
		this.planPads();
		this.planBuilds();
	}

	public EndLayout layout() {
		return this.layout;
	}

	// ------------------------------------------------------------------ islands

	private void planPads() {
		for (EndLayout.Tier t : this.tiers) {
			List<Pad> list = new ArrayList<>();
			if (t.shape() == EndLayout.Shape.ISLANDS || t.shape() == EndLayout.Shape.ISLETS) {
				list.add(new Pad(t.startZ(), 9, t.y()));
				if (t.hasExit()) list.add(new Pad(t.exitZ(), t.stalkerArena() ? 26 : 9, t.y()));
				if (t.city() > 0) list.add(new Pad(this.layout.cityZ(t), 20, t.y() + 2));
				if (t.shulkerVault()) list.add(new Pad(this.layout.shulkerVault(this.bounds, t).getZ(), 9, t.y()));
			}
			this.pads.add(list);
		}
	}

	/** An island column of tier {@code i}: {bottom, top}, or null for void. */
	private int @Nullable [] island(int i, int x, int z) {
		EndLayout.Tier t = this.tiers.get(i);
		for (Pad p : this.pads.get(i)) {
			double dx = x - this.bounds.centerX(), dz = z - p.z();
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d < p.radius()) {
				int depth = 6 + (int) ((1.0 - d / p.radius()) * 18.0);
				return new int[] {p.top() - depth, p.top()};
			}
		}
		if (t.shape() != EndLayout.Shape.ISLANDS && t.shape() != EndLayout.Shape.ISLETS) return null;
		double f = t.frequency();
		double n = this.main[i].get(x * f, z * f) * 0.75 + this.detail[i].get(x * f * 2.7, z * f * 2.7) * 0.35;
		int end = Math.min(z - EndLayout.ZONE_MIN_Z, EndLayout.ZONE_MAX_Z - z);
		if (end < 24) n -= (24 - end) / 12.0;
		if (n <= t.threshold()) return null;
		double s = Math.min(1.0, (n - t.threshold()) / (1.0 - t.threshold()) * 2.0);
		boolean islet = t.shape() == EndLayout.Shape.ISLETS;
		double bumps = t.stalkerArena() ? 1.5 : 4.0; // the barrens are flat
		int top = t.y() + (int) Math.round(this.hill[i].get(x / 24.0, z / 24.0) * bumps) + (int) (s * (islet ? 2 : 3));
		int depth = islet ? 1 + (int) (s * 9) : 2 + (int) (s * 26);
		return new int[] {top - depth, top};
	}

	/** The top block of the island column of a tier at (x, z), or {@link Integer#MIN_VALUE}. */
	public int islandTop(EndLayout.Tier tier, int x, int z) {
		int[] col = this.island(this.tiers.indexOf(tier), x, z);
		return col == null ? Integer.MIN_VALUE : col[1];
	}

	private double hash01(int x, int y, int z, long salt) {
		return (Strip.mix(this.seed ^ salt, Mth.getSeed(x, y, z)) >>> 11) * 0x1.0p-53;
	}

	// ------------------------------------------------------------------ the noise step

	/** Raw section write. */
	static void set(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
		if (y < chunk.getMinY() || y > chunk.getMaxY()) return;
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		section.setBlockState(lx, y & 15, lz, state, false);
	}

	/** The tiers and the builds of this chunk's strip columns (the noise step; vanilla terrain is already there). */
	public void fill(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int x0 = Math.max(pos.getMinBlockX(), this.bounds.minX()), x1 = Math.min(pos.getMaxBlockX(), this.bounds.maxX());
		int z0 = Math.max(pos.getMinBlockZ(), Math.max(this.bounds.minZ(), EndLayout.ZONE_MIN_Z));
		int z1 = Math.min(pos.getMaxBlockZ(), Math.min(this.bounds.maxZ(), EndLayout.ZONE_MAX_Z));
		if (x0 > x1 || z0 > z1) return;
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection section : sections) section.acquire();
		try {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
					for (int i = 0; i < this.tiers.size(); i++) {
						EndLayout.Tier t = this.tiers.get(i);
						if (t.shape() == EndLayout.Shape.CRYPT) {
							this.cryptColumn(chunk, t, lx, lz, x, z);
							continue;
						}
						int[] col = this.island(i, x, z);
						if (col == null) continue;
						for (int y = col[0]; y <= col[1]; y++) {
							BlockState state = END_STONE;
							if (t.darkTop()) state = y == col[1] ? OBSIDIAN : this.hash01(x, y, z, 0xE3D1L) < 0.06 ? CRYING_OBSIDIAN : END_STONE;
							set(chunk, lx, y, lz, state);
						}
						if (t.stalactites()) {
							// Hanging Roots: end-stone stalactites under the islands
							double sp = this.spike[i].get(x / 5.0, z / 5.0);
							int len = sp > 0.3 ? Math.min(40, (int) ((sp - 0.3) * 70.0)) : 0;
							for (int y = col[0] - 1; y >= col[0] - len; y--) set(chunk, lx, y, lz, END_STONE);
						}
					}
				}
			}
			for (Build build : this.builds) {
				if (!build.intersects(x0, z0, x1, z1)) continue;
				for (int x = Math.max(build.minX(), x0); x <= Math.min(build.maxX(), x1); x++) {
					for (int z = Math.max(build.minZ(), z0); z <= Math.min(build.maxZ(), z1); z++) {
						int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
						for (int y = build.minY(); y <= build.maxY(); y++) {
							BlockState state = build.shape().at(x, y, z);
							if (state != null) set(chunk, lx, y, lz, state);
						}
					}
				}
			}
		} finally {
			for (LevelChunkSection section : sections) section.release();
		}
	}

	/** U2, the Purpur Crypt: one purpur hall the whole strip wide, from end to end of the tier. */
	private void cryptColumn(ChunkAccess chunk, EndLayout.Tier t, int lx, int lz, int x, int z) {
		if (z < this.cryptMinZ || z > this.cryptMaxZ) return;
		int y0 = t.y();
		boolean wallX = x == this.bounds.minX() || x == this.bounds.maxX();
		boolean endWall = z == this.cryptMinZ || z == this.cryptMaxZ;
		boolean side = x == this.bounds.minX() + 1 || x == this.bounds.maxX() - 1;
		for (int y = y0 - 3; y <= y0 + 8; y++) {
			BlockState state;
			if (y < y0) state = END_STONE_BRICKS;
			else if (y == y0) state = Math.floorMod(z, 8) == 0 ? PURPUR_PILLAR : PURPUR;
			else if (y == y0 + 8) state = PURPUR;
			else if (y == y0 + 7) state = this.isAlcove(x, z) ? AIR : PURPUR;
			else if (wallX || endWall) state = PURPUR;
			else if (side && Math.floorMod(z, 12) == 0) state = PURPUR_PILLAR;
			else if (side && Math.floorMod(z, 12) == 6 && y == y0 + 4) state = rod(x == this.bounds.minX() + 1 ? Direction.EAST : Direction.WEST);
			else state = AIR;
			set(chunk, lx, y, lz, state);
		}
	}

	/** A shulker's niche in the crypt ceiling: every 20 blocks, alternating sides. */
	private boolean isAlcove(int x, int z) {
		int d = z - this.cryptMinZ;
		if (Math.floorMod(d, 20) != 10) return false;
		int side = Math.floorMod(Math.floorDiv(d, 20), 2) == 0 ? -3 : 3;
		return x == this.bounds.centerX() + side;
	}

	// ------------------------------------------------------------------ builds

	private void planBuilds() {
		// the base platform: Ascent Gate (north) and Descent Gate (south)
		List<int[]> baseGates = new ArrayList<>();
		List<BlockPos> baseTargets = new ArrayList<>();
		for (EndLayout.Gate gate : this.layout.gates(this.bounds)) {
			if (gate.name().equals("ascent") || gate.name().equals("descent")) {
				baseGates.add(new int[] {gate.pos().getZ()});
				baseTargets.add(gate.target());
			}
		}
		this.platform(EndLayout.BASE_PLATFORM_Y, EndLayout.BASE_PLATFORM_Z, 5, baseGates, baseTargets, false);
		for (EndLayout.Tier t : this.tiers) {
			switch (t.shape()) {
				case CROWN -> this.crown(t);
				case ARENA -> this.matriarchArena(t);
				default -> {}
			}
			if (t.stalkerArena()) this.stalkerArena(t);
			if (t.shulkerVault()) this.shulkerVault(t);
			EndLayout.Gate back = this.layout.gate(this.bounds, t.id() + "_return");
			EndLayout.Platform start = this.layout.startPlatform(t);
			this.platform(start.floorY(), start.z(), 3, List.<int[]>of(new int[] {start.gateZ()}), List.of(back.target()), false);
			EndLayout.Gate rift = this.layout.gate(this.bounds, t.id() + "_rift");
			if (rift != null) {
				EndLayout.Platform exit = this.layout.exitPlatform(t);
				this.platform(exit.floorY(), exit.z(), 3, List.<int[]>of(new int[] {exit.gateZ()}), List.of(rift.target()), t.stalkerArena());
			}
		}
	}

	/**
	 * A purpur gate platform 7 wide, centred on (strip centre, {@code pz}): gateways on bedrock
	 * with a bedrock cap, end rods at the corners. {@code locked}: the gateway is an arena gate
	 * block until the arena's guardian dies (E5).
	 */
	private void platform(int floorY, int pz, int halfZ, List<int[]> gates, List<BlockPos> targets, boolean locked) {
		int cx = this.bounds.centerX();
		int minX = Math.max(this.bounds.minX(), cx - 3), maxX = Math.min(this.bounds.maxX(), cx + 3);
		int minZ = pz - halfZ, maxZ = pz + halfZ;
		List<Fill> fills = new ArrayList<>();
		for (int i = 0; i < gates.size(); i++) {
			if (!locked) fills.add(new Fill(new BlockPos(cx, floorY + 1, gates.get(i)[0]), targets.get(i), null, null));
		}
		BlockState gateBlock = locked ? ModContent.ARENA_GATE.defaultBlockState() : GATEWAY;
		Shape shape = (x, y, z) -> {
			for (int[] g : gates) {
				if (x == cx && z == g[0]) {
					if (y == floorY || y == floorY + 2) return BEDROCK;
					if (y == floorY + 1) return gateBlock;
				}
			}
			if (y == floorY - 1) return END_STONE_BRICKS;
			if (y == floorY) return (x == minX || x == maxX || z == minZ || z == maxZ) ? PURPUR_PILLAR : PURPUR;
			if (y == floorY + 1 && (x == minX || x == maxX) && (z == minZ || z == maxZ)) return rod(Direction.UP);
			return AIR;
		};
		this.builds.add(new Build(minX, floorY - 1, minZ, maxX, floorY + 4, maxZ, shape, fills));
	}

	/** E5: the Void Stalker's arena, a checkered plaza around the exit platform with low walls. */
	private void stalkerArena(EndLayout.Tier t) {
		int[] zr = this.layout.stalkerArenaZ(t);
		int y0 = t.y(), cx = this.bounds.centerX();
		int near = t.exitZ() - 16 * t.dirIn();
		Shape shape = (x, y, z) -> {
			if (y == y0 - 1) return END_STONE;
			if (y == y0) return Math.floorMod(x + z, 2) == 0 ? PURPUR : END_STONE_BRICKS;
			if (z == zr[0] || z == zr[1]) {
				if (z == near && Math.abs(x - cx) <= 1 && y <= y0 + 3) return AIR;
				return y <= y0 + 4 ? PURPUR_PILLAR : AIR;
			}
			if (x == this.bounds.minX() || x == this.bounds.maxX()) return y <= y0 + 2 ? PURPUR : AIR;
			return AIR;
		};
		this.builds.add(new Build(this.bounds.minX(), y0 - 1, zr[0], this.bounds.maxX(), y0 + 8, zr[1], shape, List.of()));
	}

	/** U4: the Phantom Matriarch's closed obsidian arena on its foundation island. */
	private void matriarchArena(EndLayout.Tier t) {
		int[] zr = this.layout.matriarchArenaZ(t);
		int y0 = t.y(), top = y0 + EndLayout.ARENA_HEIGHT;
		int minX = this.bounds.minX(), maxX = this.bounds.maxX();
		double mid = (zr[0] + zr[1]) / 2.0, half = (zr[1] - zr[0]) / 2.0 + 4;
		Shape shape = (x, y, z) -> {
			if (y < y0) {
				double depth = 4 + Math.max(0.0, 1.0 - Math.abs(z - mid) / half) * 16.0;
				return y0 - y <= depth ? END_STONE : null;
			}
			if (z < zr[0] || z > zr[1]) return null;
			boolean wall = x == minX || x == maxX || z == zr[0] || z == zr[1];
			if (y == y0 || y == top || wall) return this.hash01(x, y, z, 0xA4E7L) < 0.1 ? CRYING_OBSIDIAN : OBSIDIAN;
			if (y == y0 + 1 && (x == minX + 1 || x == maxX - 1) && Math.floorMod(z, 6) == 0) return rod(Direction.UP);
			if (y == top - 1 && Math.abs(x - this.bounds.centerX()) == 2 && Math.floorMod(z, 6) == 3) return rod(Direction.DOWN);
			return AIR;
		};
		this.builds.add(new Build(minX, y0 - 20, zr[0] - 4, maxX, top, zr[1] + 4, shape, List.of()));
	}

	/** E4: a purpur room with four shulker boxes of end-city loot. */
	private void shulkerVault(EndLayout.Tier t) {
		BlockPos v = this.layout.shulkerVault(this.bounds, t);
		int cx = v.getX(), vz = v.getZ(), y0 = v.getY();
		List<Fill> fills = new ArrayList<>();
		List<BlockPos> boxes = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx += 4) {
			for (int dz = -2; dz <= 2; dz += 4) {
				BlockPos box = new BlockPos(cx + dx, y0 + 1, vz + dz);
				boxes.add(box);
				fills.add(new Fill(box, null, null, BuiltInLootTables.END_CITY_TREASURE));
			}
		}
		Shape shape = (x, y, z) -> {
			if (y == y0 || y == y0 + 5) return PURPUR;
			boolean edgeX = Math.abs(x - cx) == 3, edgeZ = Math.abs(z - vz) == 3;
			if (edgeZ && x == cx && y <= y0 + 2) return AIR; // doors at both ends
			if (edgeX || edgeZ) return (edgeX && edgeZ) ? PURPUR_PILLAR : PURPUR;
			for (BlockPos b : boxes) if (b.getX() == x && b.getY() == y && b.getZ() == z) return Blocks.SHULKER_BOX.defaultBlockState();
			if (x == cx && z == vz && y == y0 + 4) return rod(Direction.DOWN);
			return AIR;
		};
		this.builds.add(new Build(cx - 3, y0, vz - 3, cx + 3, y0 + 5, vz + 3, shape, fills));
	}

	/**
	 * The Crown: a purpur citadel on a foundation island, from just before the start platform
	 * along +z. The hoard chest stands on a dais; the Crown Vault lies behind the Void Seal.
	 */
	private void crown(EndLayout.Tier t) {
		int cx = this.bounds.centerX(), minX = this.bounds.minX(), maxX = this.bounds.maxX();
		int z0 = EndLayout.CROWN_MIN_Z, z1 = EndLayout.CROWN_MAX_Z, seal = EndLayout.CROWN_SEAL_Z;
		int floor = EndLayout.CROWN_FLOOR_Y, roof = EndLayout.CROWN_ROOF_Y;
		BlockPos hoard = EndLayout.hoardChest(this.bounds), vault = EndLayout.vaultChest(this.bounds), door = EndLayout.voidSeal(this.bounds);
		double midZ = (z0 + z1) / 2.0, halfZ = (z1 - z0) / 2.0 + 6, halfX = this.bounds.width() / 2.0 + 2;
		int[][] spires = {{minX + 1, z0 + 1}, {maxX - 1, z0 + 1}, {minX + 1, z1 - 1}, {maxX - 1, z1 - 1}, {cx, (z0 + z1) / 2}};
		BlockState voidSeal = ModContent.VOID_SEAL.defaultBlockState();
		Shape shape = (x, y, z) -> {
			if (y < floor) {
				double u = (z - midZ) / halfZ, v = (x - this.bounds.centerX()) / halfX;
				double d = 1.0 - (u * u + v * v);
				return d > 0 && floor - y <= 3 + d * 29 ? END_STONE : null;
			}
			if (z < z0 || z > z1) return null;
			for (int[] s : spires) {
				if (x == s[0] && z == s[1] && y > roof) return y == EndLayout.CROWN_SPIRE_TOP_Y ? rod(Direction.UP) : y < EndLayout.CROWN_SPIRE_TOP_Y ? PURPUR_PILLAR : null;
			}
			if (y > roof) return null;
			if (y == floor) return (x == minX || x == maxX || z == z0 || z == z1) ? PURPUR_PILLAR : PURPUR;
			if (y == roof) return PURPUR;
			boolean wall = x == minX || x == maxX || z == z0 || z == z1;
			if (wall) return y >= floor + 4 && y <= floor + 7 && Math.floorMod(z, 6) == 0 && !(z == z0 || z == z1) ? Blocks.STAINED_GLASS.purple().defaultBlockState() : PURPUR;
			if (z == seal) {
				if (x == door.getX() && (y == door.getY() || y == door.getY() + 1)) return voidSeal;
				return END_STONE_BRICKS;
			}
			if (x == hoard.getX() && z == hoard.getZ()) {
				if (y == hoard.getY() - 1) return PURPUR_PILLAR;
				if (y == hoard.getY()) return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
			}
			if (Math.abs(x - hoard.getX()) == 2 && z == hoard.getZ() && y == floor + 1) return rod(Direction.UP);
			if (x == vault.getX() && y == vault.getY() && z == vault.getZ()) return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
			if (y == floor + 1 && (x == minX + 1 || x == maxX - 1) && Math.floorMod(z, 6) == 3) return rod(Direction.UP);
			return AIR;
		};
		List<Fill> fills = List.of(
			new Fill(hoard, null, List.of(
				new ItemStack(Items.DRAGON_HEAD), new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Items.TOTEM_OF_UNDYING),
				new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2), new ItemStack(Items.MUSIC_DISC_RELIC)
			), null),
			new Fill(vault, null, List.of(new ItemStack(Items.ELYTRA), new ItemStack(Items.BEACON), new ItemStack(Items.NETHERITE_BLOCK, 4)), null)
		);
		this.builds.add(new Build(minX, EndLayout.CROWN_FOUNDATION_Y, z0 - 8, maxX, EndLayout.CROWN_SPIRE_TOP_Y, z1 + 8, shape, fills));
	}

	// ------------------------------------------------------------------ decoration

	/**
	 * After the chunk's vanilla decoration: the block entities of the builds (gateway exits,
	 * chests, shulker boxes), chorus on E1 and E4, end-rod groves on E4 and the crypt's shulkers.
	 */
	public void decorate(WorldGenLevel level, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int x0 = Math.max(pos.getMinBlockX(), this.bounds.minX()), x1 = Math.min(pos.getMaxBlockX(), this.bounds.maxX());
		int z0 = Math.max(pos.getMinBlockZ(), Math.max(this.bounds.minZ(), EndLayout.ZONE_MIN_Z));
		int z1 = Math.min(pos.getMaxBlockZ(), Math.min(this.bounds.maxZ(), EndLayout.ZONE_MAX_Z));
		if (x0 > x1 || z0 > z1) return;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 17500), Strip.mix(pos.x(), pos.z()));
		for (Build build : this.builds) {
			for (Fill fill : build.fills()) {
				BlockPos p = fill.pos();
				if (p.getX() < x0 || p.getX() > x1 || p.getZ() < z0 || p.getZ() > z1) continue;
				BlockState want = build.shape().at(p.getX(), p.getY(), p.getZ());
				if (want == null) continue;
				level.setBlock(p, want, Block.UPDATE_CLIENTS); // creates the block entity the noise step left out
				if (fill.gatewayExit() != null && level.getBlockEntity(p) instanceof TheEndGatewayBlockEntity gateway) {
					gateway.setExitPosition(fill.gatewayExit(), true);
				} else if (fill.loot() != null) {
					RandomizableContainer.setBlockEntityLootTable(level, random, p, fill.loot());
				} else if (fill.items() != null && level.getBlockEntity(p) instanceof ChestBlockEntity chest) {
					for (int i = 0; i < fill.items().size(); i++) chest.setItem(4 + i * 2, fill.items().get(i).copy());
				}
			}
		}
		double area = (x1 - x0 + 1) * (z1 - z0 + 1) / 256.0;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int i = 0; i < this.tiers.size(); i++) {
			EndLayout.Tier t = this.tiers.get(i);
			RandomSource r = new XoroshiroRandomSource(Strip.mix(this.seed, 17600 + i), Strip.mix(pos.x(), pos.z()));
			if (t.chorus()) {
				int count = count(r, (t.endRods() ? 2.0 : 5.0) * area);
				for (int k = 0; k < count; k++) {
					int x = x0 + r.nextInt(x1 - x0 + 1), z = z0 + r.nextInt(z1 - z0 + 1);
					if (this.nearBuild(i, x, z, 6)) continue;
					int top = this.islandTop(t, x, z);
					if (top == Integer.MIN_VALUE) continue;
					BlockPos origin = new BlockPos(x, top + 1, z);
					if (level.isEmptyBlock(origin) && level.getBlockState(origin.below()).is(Blocks.END_STONE)) ChorusFlowerBlock.generatePlant(level, origin, r, 8);
				}
			}
			if (t.endRods()) {
				int groves = count(r, 1.5 * area);
				for (int k = 0; k < groves; k++) {
					int gx = x0 + r.nextInt(x1 - x0 + 1), gz = z0 + r.nextInt(z1 - z0 + 1);
					for (int s = 0; s < 4 + r.nextInt(4); s++) {
						int x = Math.clamp(gx + r.nextInt(7) - 3, x0, x1), z = Math.clamp(gz + r.nextInt(7) - 3, z0, z1);
						if (this.nearBuild(i, x, z, 4)) continue;
						int top = this.islandTop(t, x, z);
						if (top == Integer.MIN_VALUE || !level.getBlockState(at.set(x, top, z)).is(Blocks.END_STONE)) continue;
						int h = 1 + r.nextInt(4);
						for (int y = top + 1; y <= top + h && level.isEmptyBlock(at.set(x, y, z)); y++) level.setBlock(at, rod(Direction.UP), Block.UPDATE_CLIENTS);
					}
				}
			}
			if (t.shape() == EndLayout.Shape.CRYPT) {
				for (int x = x0; x <= x1; x++) {
					for (int z = Math.max(z0, this.cryptMinZ); z <= Math.min(z1, this.cryptMaxZ); z++) {
						if (!this.isAlcove(x, z)) continue;
						Shulker shulker = EntityTypes.SHULKER.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
						if (shulker == null) continue;
						shulker.snapTo(x + 0.5, t.y() + 7, z + 0.5, 0.0F, 0.0F);
						shulker.setPersistenceRequired();
						level.addFreshEntity(shulker);
					}
				}
			}
		}
	}

	private static int count(RandomSource random, double expected) {
		return (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
	}

	/** True near a platform, city site or vault of tier {@code i} (and inside any build box). */
	private boolean nearBuild(int i, int x, int z, int margin) {
		for (Pad p : this.pads.get(i)) if (Math.abs(z - p.z()) <= p.radius() + margin) return true;
		int y = this.tiers.get(i).y();
		for (Build b : this.builds) {
			if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin && y + 8 >= b.minY() && y - 8 <= b.maxY()) return true;
		}
		return false;
	}
}
