package dev.antcraft;

import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/**
 * {@code /antcraft selfcheck} (ops only): checks the strip invariants in every loaded chunk of the
 * strip dimensions and prints one PASS / FAIL / SKIP line per check. Only chunks that are already
 * loaded are read, nothing is generated. {@code tools/worldcheck.sh} runs it over RCON.
 */
public final class SelfCheck {
	private SelfCheck() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("antcraft")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("selfcheck").executes(SelfCheck::run))
		));
	}

	private enum Result { PASS, FAIL, SKIP }

	private record Line(Result result, String dimension, String name, String detail) {}

	/** Collects the problems of one check (keeps the first few positions for the message). */
	private static final class Problems {
		int count;
		final List<String> examples = new ArrayList<>();

		void add(int x, int y, int z, Object what) {
			if (this.count++ < 6) this.examples.add("(" + x + " " + y + " " + z + ") " + what);
		}

		@Override
		public String toString() {
			return this.count + " bad" + (this.examples.isEmpty() ? "" : ", e.g. " + String.join(", ", this.examples));
		}
	}

	private static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<Line> lines = new ArrayList<>();
		for (ServerLevel level : source.getServer().getAllLevels()) {
			StripBounds bounds = Strip.bounds(level);
			if (bounds == null) continue;
			new Checker(level, bounds, lines).run();
		}
		int pass = 0, fail = 0, skip = 0;
		for (Line line : lines) {
			ChatFormatting color = switch (line.result()) {
				case PASS -> { pass++; yield ChatFormatting.GREEN; }
				case FAIL -> { fail++; yield ChatFormatting.RED; }
				case SKIP -> { skip++; yield ChatFormatting.GRAY; }
			};
			Component text = Component.literal(line.result() + " ").withStyle(color)
				.append(Component.literal(line.dimension() + " " + line.name() + ": " + line.detail()).withStyle(ChatFormatting.WHITE));
			source.sendSuccess(() -> text, false);
			AntCraft.LOGGER.info("selfcheck {} {} {}: {}", line.result(), line.dimension(), line.name(), line.detail());
		}
		if (lines.isEmpty()) {
			source.sendFailure(Component.literal("AntCraft selfcheck: no strip dimension in this world"));
			return 0;
		}
		String summary = "AntCraft selfcheck: " + pass + " PASS, " + fail + " FAIL, " + skip + " SKIP";
		ChatFormatting summaryColor = fail == 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
		source.sendSuccess(() -> Component.literal(summary).withStyle(summaryColor), false);
		AntCraft.LOGGER.info(summary);
		return fail == 0 ? 1 : 0;
	}

	private static final class Checker {
		private final ServerLevel level;
		private final StripBounds bounds;
		private final List<Line> lines;
		private final String dimension;
		private final long seed;

		Checker(ServerLevel level, StripBounds bounds, List<Line> lines) {
			this.level = level;
			this.bounds = bounds;
			this.lines = lines;
			this.dimension = level.dimension().identifier().getPath();
			this.seed = level.getSeed();
		}

		private void report(String name, boolean ok, String detail) {
			this.lines.add(new Line(ok ? Result.PASS : Result.FAIL, this.dimension, name, detail));
		}

		private void skip(String name, String why) {
			this.lines.add(new Line(Result.SKIP, this.dimension, name, why));
		}

		private @Nullable LevelChunk chunk(int cx, int cz) {
			return this.level.getChunkSource().getChunkNow(cx, cz);
		}

		/** The block, or null if its chunk is not loaded. */
		private @Nullable BlockState block(int x, int y, int z) {
			LevelChunk chunk = this.chunk(x >> 4, z >> 4);
			return chunk == null ? null : chunk.getBlockState(new BlockPos(x, y, z));
		}

		/** Loaded chunks overlapping the strip itself. */
		private List<LevelChunk> stripChunks() {
			List<LevelChunk> list = new ArrayList<>();
			for (int cx = this.bounds.minX() >> 4; cx <= this.bounds.maxX() >> 4; cx++) {
				for (int cz = this.bounds.minZ() >> 4; cz <= this.bounds.maxZ() >> 4; cz++) {
					LevelChunk chunk = this.chunk(cx, cz);
					if (chunk != null) list.add(chunk);
				}
			}
			return list;
		}

		void run() {
			List<LevelChunk> strip = this.stripChunks();
			int total = ((this.bounds.maxX() >> 4) - (this.bounds.minX() >> 4) + 1) * ((this.bounds.maxZ() >> 4) - (this.bounds.minZ() >> 4) + 1);
			String coverage = strip.size() + "/" + total + " strip chunks loaded, strip " + this.bounds.width() + " x " + this.bounds.length()
				+ " (x " + this.bounds.minX() + ".." + this.bounds.maxX() + ", z " + this.bounds.minZ() + ".." + this.bounds.maxZ() + ")";
			if (strip.isEmpty()) {
				this.skip("chunks", coverage);
				return;
			}
			if (strip.size() == total) {
				this.report("chunks", true, coverage);
			} else {
				this.skip("chunks", coverage + "; only the loaded part is checked");
			}
			ChunkGeneratorKind kind = ChunkGeneratorKind.of(this.level);
			if (kind == ChunkGeneratorKind.OVERWORLD) {
				for (int k = 0; k < Strip.FLOOR_COUNT; k++) this.checkFloor(k);
				for (int k = 0; k < Strip.FLOOR_COUNT; k++) this.checkSeal(k);
				for (int k : Strip.GUARDIAN_FLOORS) this.checkBossRoom(k);
				this.checkLeaves(strip);
				this.checkPortalRoom();
			}
			this.checkWallsAndOutside(kind);
			if (kind == ChunkGeneratorKind.NETHER) this.checkShrine(((NetherStripChunkGenerator) this.level.getChunkSource().getGenerator()).layout());
		}

		// ------------------------------------------------------------------ floors and seals

		private void checkFloor(int k) {
			int hx = Strip.holeX(this.seed, k, this.bounds), hz = Strip.holeZ(this.seed, k, this.bounds);
			int base = Strip.floorBase(k), top = Strip.floorTop(k);
			Problems bad = new Problems();
			int columns = 0;
			for (LevelChunk chunk : this.stripChunks()) {
				ChunkPos pos = chunk.getPos();
				for (int x = Math.max(pos.getMinBlockX(), this.bounds.minX()); x <= Math.min(pos.getMaxBlockX(), this.bounds.maxX()); x++) {
					for (int z = Math.max(pos.getMinBlockZ(), this.bounds.minZ()); z <= Math.min(pos.getMaxBlockZ(), this.bounds.maxZ()); z++) {
						columns++;
						for (int y = base; y <= top; y++) {
							BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
							boolean ok;
							if (x == hx && z == hz) {
								ok = state.is(Blocks.LADDER) || (y == top && state.getBlock() instanceof SealBlock);
							} else if (y == top && Math.abs(x - hx) == 1 && Math.abs(z - hz) == 1) {
								ok = state.is(Blocks.GLOWSTONE);
							} else {
								ok = state.is(Blocks.BEDROCK);
							}
							if (!ok) bad.add(x, y, z, name(state));
						}
					}
				}
			}
			boolean holeLoaded = this.chunk(hx >> 4, hz >> 4) != null;
			this.report("floor " + k, bad.count == 0,
				"y " + base + ".." + top + ", " + columns + " columns, one hole at (" + hx + " " + hz + ")" + (holeLoaded ? "" : " (not loaded)") + ", " + bad);
		}

		private void checkSeal(int k) {
			String name = "seal " + k;
			int hx = Strip.holeX(this.seed, k, this.bounds), hz = Strip.holeZ(this.seed, k, this.bounds), top = Strip.floorTop(k);
			BlockState state = this.block(hx, top, hz);
			if (state == null) {
				this.skip(name, "hole chunk not loaded");
				return;
			}
			AntCraftConfig config = AntCraftConfig.get();
			Block seal = k == Strip.SEALED_FLOOR ? ModContent.NETHER_SEAL
				: k == Strip.ANCIENT_SEALED_FLOOR ? ModContent.ANCIENT_SEAL
				: Strip.isGuardianFloor(k) ? ModContent.GUARDIAN_SEAL : null;
			boolean gateOn = k == Strip.SEALED_FLOOR ? config.netherKeyGate
				: k == Strip.ANCIENT_SEALED_FLOOR ? config.ancientKeyGate
				: Strip.isGuardianFloor(k) && config.guardians;
			String where = "(" + hx + " " + top + " " + hz + ") " + name(state);
			if (seal == null) {
				this.report(name, state.is(Blocks.LADDER), where + ", expected an open ladder");
			} else if (state.is(seal)) {
				this.report(name, true, where + (gateOn ? "" : " (gate is off in the config now; floors generated while it was on stay sealed)"));
			} else if (state.is(Blocks.LADDER)) {
				if (Strip.isGuardianFloor(k) && gateOn) {
					boolean cleared = ArenaManager.state(this.level.getServer().overworld(), k) == ArenaManager.CLEARED;
					this.report(name, cleared, where + (cleared ? " (guardian defeated)" : ", expected " + name(seal.defaultBlockState()) + " (guardian not defeated)"));
				} else {
					this.report(name, true, where + (gateOn ? " (opened with the key)" : " (gate off)"));
				}
			} else {
				this.report(name, false, where + ", expected " + name(seal.defaultBlockState()) + " or a ladder");
			}
		}

		// ------------------------------------------------------------------ boss rooms

		private void checkBossRoom(int k) {
			String name = "boss room " + k;
			int hx = Strip.holeX(this.seed, k, this.bounds), hz = Strip.holeZ(this.seed, k, this.bounds), floorTop = Strip.floorTop(k);
			BlockState hole = this.block(hx, floorTop, hz);
			boolean expected = hole != null && hole.is(ModContent.GUARDIAN_SEAL)
				|| (hole == null || hole.is(Blocks.LADDER)) && AntCraftConfig.get().guardians;
			if (!expected) {
				this.skip(name, "guardians are off and the hole is open");
				return;
			}
			boolean fighting = ArenaManager.state(this.level.getServer().overworld(), k) == ArenaManager.FIGHTING;
			int top = floorTop + Strip.HALL_HEIGHT + ArenaManager.WALL_EXTRA_HEIGHT;
			Problems bad = new Problems();
			int checked = 0;
			for (int side = -1; side <= 1; side += 2) {
				int z = ArenaManager.wallZ(this.seed, k, this.bounds, side);
				if (z < this.bounds.minZ() || z > this.bounds.maxZ() || this.chunk(this.bounds.minX() >> 4, z >> 4) == null) continue;
				for (int x = this.bounds.minX(); x <= this.bounds.maxX(); x++) {
					for (int y = floorTop + 1; y <= top; y++) {
						BlockState state = this.block(x, y, z);
						if (state == null) continue;
						checked++;
						boolean ok = ArenaManager.isDoorway(this.bounds, k, x, y)
							? state.isAir() || (fighting && state.is(ModContent.ARENA_GATE))
							: state.is(Blocks.POLISHED_BLACKSTONE_BRICKS);
						if (!ok) bad.add(x, y, z, name(state));
					}
				}
			}
			if (checked == 0) {
				this.skip(name, "wall chunks not loaded");
				return;
			}
			this.report(name, bad.count == 0, "walls at z " + (hz - ArenaManager.WALL_OFFSET) + " / " + (hz + ArenaManager.WALL_OFFSET)
				+ ", doorways " + (fighting ? "closed (fight running)" : "open") + ", " + checked + " blocks, " + bad);
		}

		// ------------------------------------------------------------------ leaves

		/** No leaves that would decay by themselves right away (distance 7, not persistent). */
		private void checkLeaves(List<LevelChunk> strip) {
			Problems bad = new Problems();
			int leaves = 0;
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			for (LevelChunk chunk : strip) {
				ChunkPos pos = chunk.getPos();
				for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
					int y0 = Math.max(Strip.forestFloor(layer) - DeepTerrain.LEAF_BAND_BELOW, Strip.layerBottom(layer));
					int y1 = Math.min(Strip.forestFloor(layer) + DeepTerrain.LEAF_BAND_ABOVE, Strip.layerTop(layer));
					for (int y = y0; y <= y1; y++) {
						LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
						if (section.hasOnlyAir()) {
							y = ((y >> 4) << 4) + 15; // skip the rest of the section
							continue;
						}
						for (int x = Math.max(pos.getMinBlockX(), this.bounds.minX()); x <= Math.min(pos.getMaxBlockX(), this.bounds.maxX()); x++) {
							for (int z = Math.max(pos.getMinBlockZ(), this.bounds.minZ()); z <= Math.min(pos.getMaxBlockZ(), this.bounds.maxZ()); z++) {
								BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
								if (!(state.getBlock() instanceof LeavesBlock) || !state.hasProperty(LeavesBlock.DISTANCE)) continue;
								leaves++;
								if (state.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE && !state.getValue(LeavesBlock.PERSISTENT)) {
									bad.add(x, y, z, name(state));
								}
							}
						}
					}
				}
			}
			this.report("forest leaves", bad.count == 0, leaves + " leaves in the deep forests, decaying (distance 7): " + bad);
		}

		// ------------------------------------------------------------------ walls and outside

		private void checkWallsAndOutside(ChunkGeneratorKind kind) {
			int minY = this.level.getMinY(), maxY = this.level.getMaxY();
			Problems wallBad = new Problems(), outsideBad = new Problems();
			int wallColumns = 0, outsideColumns = 0, splitMin = Integer.MAX_VALUE, splitMax = Integer.MIN_VALUE;
			for (int cx = (this.bounds.wallMinX() >> 4) - 1; cx <= (this.bounds.wallMaxX() >> 4) + 1; cx++) {
				for (int cz = (this.bounds.wallMinZ() >> 4) - 1; cz <= (this.bounds.wallMaxZ() >> 4) + 1; cz++) {
					LevelChunk chunk = this.chunk(cx, cz);
					if (chunk == null) continue;
					LevelChunkSection[] sections = chunk.getSections();
					for (int lx = 0; lx < 16; lx++) {
						for (int lz = 0; lz < 16; lz++) {
							int x = (cx << 4) + lx, z = (cz << 4) + lz;
							if (this.bounds.inside(x, z)) continue;
							if (this.bounds.isWall(x, z)) {
								wallColumns++;
								// overworld: bedrock from the bottom up to the split, barrier above; Nether / End: all barrier
								boolean barrierSeen = kind != ChunkGeneratorKind.OVERWORLD;
								int split = minY;
								for (int y = minY; y <= maxY; y++) {
									BlockState state = sections[(y - minY) >> 4].getBlockState(lx, y & 15, lz);
									if (!barrierSeen && state.is(Blocks.BEDROCK)) continue;
									if (!barrierSeen) {
										barrierSeen = true;
										split = y;
										if (y == minY) wallBad.add(x, y, z, "no bedrock at the bottom");
									}
									if (!state.is(Blocks.BARRIER)) wallBad.add(x, y, z, name(state));
								}
								if (kind == ChunkGeneratorKind.OVERWORLD) {
									splitMin = Math.min(splitMin, split);
									splitMax = Math.max(splitMax, split);
								}
							} else {
								outsideColumns++;
								for (int i = 0; i < sections.length; i++) {
									if (sections[i].hasOnlyAir()) continue;
									for (int ly = 0; ly < 16; ly++) {
										BlockState state = sections[i].getBlockState(lx, ly, lz);
										if (!state.isAir()) outsideBad.add(x, minY + i * 16 + ly, z, name(state));
									}
								}
							}
						}
					}
				}
			}
			String wallRule = kind == ChunkGeneratorKind.OVERWORLD
				? "bedrock below the split, barrier above" + (splitMin <= splitMax ? " (split y " + splitMin + ".." + splitMax + ")" : "")
				: "barrier all the way up";
			if (wallColumns == 0) {
				this.skip("walls", "no wall column loaded");
			} else {
				this.report("walls", wallBad.count == 0, wallColumns + " columns, " + wallRule + ", " + wallBad);
			}
			if (outsideColumns == 0) {
				this.skip("outside air", "no outside column loaded");
			} else {
				this.report("outside air", outsideBad.count == 0, outsideColumns + " columns outside the walls, " + outsideBad);
			}
		}

		// ------------------------------------------------------------------ portal room

		private void checkPortalRoom() {
			int cz = Strip.portalRoomZ(this.seed, this.bounds);
			int minX = PortalRoom.minX(this.bounds), maxX = PortalRoom.maxX(this.bounds), px = PortalRoom.portalX(this.bounds);
			int minZ = cz - PortalRoom.HALF_LENGTH, maxZ = cz + PortalRoom.HALF_LENGTH - 1;
			int floorY = Strip.portalRoomFloorY(), ceilY = floorY + PortalRoom.HEIGHT, fy = floorY + 2;
			for (int x : new int[] {minX, maxX}) {
				for (int z = minZ; z < maxZ + 16; z += 16) {
					if (this.chunk(x >> 4, Math.min(z, maxZ) >> 4) == null) {
						this.skip("portal room", "room chunks not loaded (z " + minZ + ".." + maxZ + ")");
						return;
					}
				}
			}
			Problems bad = new Problems();
			// shell: stone bricks (any of the three kinds), doorways in both end walls
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int y = floorY; y <= ceilY; y++) {
						boolean shell = x == minX || x == maxX || z == minZ || z == maxZ || y == floorY || y == ceilY;
						if (!shell) continue;
						boolean doorway = (z == minZ || z == maxZ) && x >= px - 1 && x <= px + 2 && y >= floorY + 1 && y <= floorY + 3;
						BlockState state = this.block(x, y, z);
						boolean ok = doorway ? !state.isSolid()
							: state.is(Blocks.STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS) || state.is(Blocks.CRACKED_STONE_BRICKS)
								|| (y == floorY + 1 && (state.is(Blocks.LANTERN) || state.is(Blocks.CHEST) || state.is(Blocks.SPAWNER)));
						if (!ok) bad.add(x, y, z, name(state));
					}
				}
			}
			// the 12 frames, each facing the centre
			int frames = 0, eyes = 0;
			for (int i = -1; i <= 1; i++) {
				frames += this.frame(bad, px + i, fy, cz - 2, Direction.SOUTH);
				frames += this.frame(bad, px + i, fy, cz + 2, Direction.NORTH);
				frames += this.frame(bad, px - 2, fy, cz + i, Direction.EAST);
				frames += this.frame(bad, px + 2, fy, cz + i, Direction.WEST);
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockState lava = this.block(px + dx, floorY + 1, cz + dz);
					if (!lava.is(Blocks.LAVA)) bad.add(px + dx, floorY + 1, cz + dz, name(lava) + " (lava pool)");
					BlockState inside = this.block(px + dx, fy, cz + dz);
					if (!inside.isAir() && !inside.is(Blocks.END_PORTAL)) bad.add(px + dx, fy, cz + dz, name(inside) + " (portal)");
				}
			}
			for (BlockPos frame : framePositions(px, fy, cz)) {
				BlockState state = this.block(frame.getX(), frame.getY(), frame.getZ());
				if (state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE)) eyes++;
			}
			this.expect(bad, PortalRoom.spawnerPos(this.seed, this.bounds), Blocks.SPAWNER);
			this.expect(bad, PortalRoom.chestA(this.seed, this.bounds), Blocks.CHEST);
			this.expect(bad, PortalRoom.chestB(this.seed, this.bounds), Blocks.CHEST);
			this.report("portal room", bad.count == 0 && frames == 12,
				"x " + minX + ".." + maxX + " z " + minZ + ".." + maxZ + " y " + floorY + ".." + ceilY + ", " + frames + "/12 frames (" + eyes + " eyes), " + bad);
		}

		private static List<BlockPos> framePositions(int px, int fy, int cz) {
			List<BlockPos> list = new ArrayList<>();
			for (int i = -1; i <= 1; i++) {
				list.add(new BlockPos(px + i, fy, cz - 2));
				list.add(new BlockPos(px + i, fy, cz + 2));
				list.add(new BlockPos(px - 2, fy, cz + i));
				list.add(new BlockPos(px + 2, fy, cz + i));
			}
			return list;
		}

		private int frame(Problems bad, int x, int y, int z, Direction facing) {
			BlockState state = this.block(x, y, z);
			if (state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.FACING) == facing) return 1;
			bad.add(x, y, z, name(state) + " (frame facing " + facing.getName() + ")");
			return 0;
		}

		private void expect(Problems bad, BlockPos pos, Block block) {
			BlockState state = this.block(pos.getX(), pos.getY(), pos.getZ());
			if (state == null) {
				bad.add(pos.getX(), pos.getY(), pos.getZ(), "not loaded");
			} else if (!state.is(block)) {
				bad.add(pos.getX(), pos.getY(), pos.getZ(), name(state) + ", expected " + name(block.defaultBlockState()));
			}
		}

		// ------------------------------------------------------------------ Nether key shrine

		private void checkShrine(int layout) {
			int minX = KeyShrine.minX(this.bounds), maxX = minX + KeyShrine.SIZE - 1;
			int minZ = KeyShrine.minZ(this.bounds, layout), maxZ = minZ + KeyShrine.SIZE - 1;
			int midX = minX + KeyShrine.SIZE / 2 - 1, midZ = minZ + KeyShrine.SIZE / 2 - 1;
			for (int x : new int[] {minX, maxX}) {
				for (int z : new int[] {minZ, maxZ}) {
					if (this.chunk(x >> 4, z >> 4) == null) {
						this.skip("key shrine", "shrine chunks not loaded (z " + minZ + ".." + maxZ + ")");
						return;
					}
				}
			}
			BlockPos chest = KeyShrine.chestPos(this.bounds, layout);
			List<BlockPos> special = List.of(
				chest, chest.below(),
				new BlockPos(minX + 1, KeyShrine.FLOOR_Y + 1, minZ + 1), new BlockPos(maxX - 1, KeyShrine.FLOOR_Y + 1, maxZ - 1),
				new BlockPos(maxX - 1, KeyShrine.FLOOR_Y + 1, minZ + 1), new BlockPos(minX + 1, KeyShrine.FLOOR_Y + 1, maxZ - 1)
			);
			Problems bad = new Problems();
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int y = KeyShrine.FLOOR_Y; y <= KeyShrine.CEIL_Y; y++) {
						if (special.contains(new BlockPos(x, y, z))) continue;
						boolean shell = x == minX || x == maxX || z == minZ || z == maxZ || y == KeyShrine.FLOOR_Y || y == KeyShrine.CEIL_Y;
						boolean doorZ = (z == minZ || z == maxZ) && (x == midX || x == midX + 1) && y > KeyShrine.FLOOR_Y && y <= KeyShrine.FLOOR_Y + 3;
						boolean doorX = (x == minX || x == maxX) && (z == midZ || z == midZ + 1) && y > KeyShrine.FLOOR_Y && y <= KeyShrine.FLOOR_Y + 3;
						BlockState state = this.block(x, y, z);
						boolean ok = !shell || doorZ || doorX
							? !state.isSolid()
							: state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.RED_NETHER_BRICKS) || state.is(Blocks.SHROOMLIGHT);
						if (!ok) bad.add(x, y, z, name(state));
					}
				}
			}
			this.expect(bad, chest, Blocks.CHEST);
			this.expect(bad, chest.below(), Blocks.CHISELED_NETHER_BRICKS);
			this.expect(bad, special.get(2), Blocks.SPAWNER);
			this.expect(bad, special.get(3), Blocks.SPAWNER);
			boolean key = this.level.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity be
				&& be.countItem(ModContent.NETHER_KEY) > 0;
			this.report("key shrine", bad.count == 0, "x " + minX + ".." + maxX + " z " + minZ + ".." + maxZ + ", key "
				+ (key ? "in the chest" : "not in the chest (taken?)") + ", " + bad);
		}

		private static String name(@Nullable BlockState state) {
			if (state == null) return "not loaded";
			return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		}
	}

	private enum ChunkGeneratorKind {
		OVERWORLD, NETHER, END;

		static ChunkGeneratorKind of(ServerLevel level) {
			Object generator = level.getChunkSource().getGenerator();
			if (generator instanceof NetherStripChunkGenerator) return NETHER;
			if (generator instanceof EndStripChunkGenerator) return END;
			return OVERWORLD;
		}
	}
}
