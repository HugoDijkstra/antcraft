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
import net.minecraft.world.level.levelgen.structure.StructureStart;
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
				.then(Commands.literal("audit").requires(source -> StructureAudit.enabled())
					.then(Commands.literal("structures").executes(StructureAudit::run))
					.then(Commands.literal("vault").then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1000))
						.executes(c -> StructureAudit.vault(c, 0, 0))
						.then(Commands.argument("width", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 200))
							.then(Commands.argument("length", com.mojang.brigadier.arguments.IntegerArgumentType.integer(16, 30000))
								.executes(c -> StructureAudit.vault(c, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "width"),
									com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "length"))))))))
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
				StripLayout layout = Strip.layout(this.level);
				this.report("layout", layout != null, layout == null ? "not a strip overworld generator" : "layout " + layout.id + ", " + layout.floorCount
					+ " floors, vanilla from y " + layout.vanillaMinY + ", world y " + layout.worldMinY + ".." + layout.worldMaxY);
				if (layout != null) {
					for (int k = 0; k < layout.floorCount; k++) this.checkFloor(layout, k);
					for (int k = 0; k < layout.floorCount; k++) this.checkSeal(layout, k);
					for (int k : layout.guardianFloors()) this.checkBossRoom(layout, k);
					this.checkLeaves(layout, strip);
					this.checkPortalRoom(layout);
					if (layout.id >= 2) {
						this.checkLayerTable(layout);
						for (int l = 1; l <= layout.deepLayers; l++) this.checkLayer(layout, l, strip);
						this.checkDeepStarts(layout);
						this.checkVault(layout);
						this.checkVillages(layout, strip);
					}
				}
			}
			this.checkWallsAndOutside(kind);
			if (kind == ChunkGeneratorKind.NETHER) this.checkShrine(((NetherStripChunkGenerator) this.level.getChunkSource().getGenerator()).layout());
		}

		// ------------------------------------------------------------------ the layer table (tall layouts)

		/**
		 * Spec v2 3.2 and 2.4: no two rows share a (base, patch) pair, a forest biome or a non-empty tree
		 * list; no frozen or reef layer holds the vanilla igloo / ocean ruin placeholder heights (y 50..90).
		 */
		private void checkLayerTable(StripLayout layout) {
			List<String> bad = new ArrayList<>();
			java.util.Set<String> pairs = new java.util.HashSet<>();
			java.util.Set<Object> biomes = new java.util.HashSet<>(), trees = new java.util.HashSet<>();
			for (int l = 1; l <= layout.deepLayers; l++) {
				LayerSpec row = layout.layer(l);
				if (!pairs.add(row.baseStone() + "/" + row.patchStone())) bad.add("L" + l + " stones");
				if (!biomes.add(row.forestBiome())) bad.add("L" + l + " forest biome");
				if (!row.trees().isEmpty() && !trees.add(row.trees())) bad.add("L" + l + " trees");
			}
			for (int y = 50; y <= 90; y++) {
				int l = layout.layerAt(y);
				if (l >= 1 && (layout.layer(l).theme() == LayerTheme.FROZEN_DEPTHS || layout.layer(l).theme() == LayerTheme.SUNKEN_REEF)) bad.add("y " + y + " in L" + l);
			}
			this.report("layer table", bad.isEmpty(), layout.deepLayers + " rows distinct, placeholder band y 50..90 clear" + (bad.isEmpty() ? "" : "; bad " + bad));
		}

		// ------------------------------------------------------------------ per-layer content (tall layouts)

		/**
		 * One deep layer: its cave stone is the row's base/patch stone (plus ores), its forest floor
		 * has the row's ground, the forest band is open air (the vault: solid), and the biome in the
		 * forest is the row's forest biome.
		 */
		private void checkLayer(StripLayout layout, int l, List<LevelChunk> strip) {
			LayerSpec row = layout.layer(l);
			DeepTerrain terrain = DeepTerrain.forSeed(this.seed, this.bounds, layout);
			int stone = 0, stoneOk = 0, ground = 0, groundOk = 0, air = 0, band = 0, biome = 0, biomeOk = 0;
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			int ff = layout.forestFloor(l);
			for (LevelChunk chunk : strip) {
				ChunkPos pos = chunk.getPos();
				for (int x = Math.max(pos.getMinBlockX(), this.bounds.minX()); x <= Math.min(pos.getMaxBlockX(), this.bounds.maxX()); x += 3) {
					for (int z = Math.max(pos.getMinBlockZ(), this.bounds.minZ()); z <= Math.min(pos.getMaxBlockZ(), this.bounds.maxZ()); z += 3) {
						// cave stone a little above the hall
						for (int y = layout.layerBottom(l) + layout.hallHeight + 2; y < ff - 14; y += 5) {
							BlockState state = chunk.getBlockState(at.set(x, y, z));
							if (state.isAir() || !state.getFluidState().isEmpty()) continue;
							stone++;
							String n = name(state);
							if (state == row.baseStone() || state == row.patchStone() || n.endsWith("_ore") || n.contains("terracotta") || state.is(Blocks.ANCIENT_DEBRIS)
								|| state.is(Blocks.OBSIDIAN) || state.is(Blocks.GILDED_BLACKSTONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)
								|| state.is(Blocks.SCULK) || state.is(Blocks.SCULK_VEIN) || state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.AMETHYST_BLOCK)
								|| state.is(Blocks.BUDDING_AMETHYST) || n.contains("sulfur") || state.is(Blocks.BLUE_ICE)) stoneOk++;
						}
						if (!row.hasForest()) {
							for (int y = layout.layerBottom(l); y <= layout.layerTop(l); y += 7) {
								band++;
								if (chunk.getBlockState(at.set(x, y, z)).isAir()) air++;
							}
							continue;
						}
						int surface = terrain.surfaceAt(l, x, z);
						BlockState top = chunk.getBlockState(at.set(x, surface, z));
						if (!terrain.isPond(l, x, z)) {
							ground++;
							if (UndergroundForest.isGroundBlock(top, layout) || top.is(Blocks.POWDER_SNOW) || top.is(Blocks.SCULK) || top.is(Blocks.MUD)) groundOk++;
						}
						for (int y = ff + 6; y <= ff + 30; y += 6) {
							band++;
							BlockState state = chunk.getBlockState(at.set(x, y, z));
							if (!state.isSolid() || state.is(Blocks.LIGHT)) air++;
						}
						biome++;
						if (row.forestBiome() != null && this.level.getBiome(at.set(x, ff + 4, z)).is(row.forestBiome())) biomeOk++;
					}
				}
			}
			String theme = row.theme() == null ? "?" : row.theme().englishName;
			if (stone + band == 0) {
				this.skip("layer " + l, theme + ": no loaded columns");
				return;
			}
			boolean ok;
			String detail;
			if (!row.hasForest()) {
				ok = stoneOk >= stone * 0.9 && air <= band * 0.35;
				detail = String.format("%s (solid): stone %d/%d, open %d/%d", theme, stoneOk, stone, air, band);
			} else {
				ok = stoneOk >= stone * 0.85 && groundOk >= ground * 0.6 && air >= band * 0.5 && biomeOk >= biome * 0.95;
				detail = String.format("%s: stone %d/%d, ground %d/%d, forest air %d/%d, biome %s %d/%d", theme, stoneOk, stone, groundOk, ground, air, band,
					row.forestBiome() == null ? "-" : row.forestBiome().identifier(), biomeOk, biome);
			}
			this.report("layer " + l, ok, detail);
		}

		/** Every extra deep start of the layout exists in its chunk, once, at its deep height. */
		private void checkDeepStarts(StripLayout layout) {
			if (!(this.level.getChunkSource().getGenerator() instanceof StripChunkGenerator generator)) return;
			var structures = this.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
			List<String> bad = new ArrayList<>(), good = new ArrayList<>();
			java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>, ChunkPos> expected = new java.util.LinkedHashMap<>();
			java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>, Integer> layers = new java.util.HashMap<>();
			for (DeepStarts.Planned plan : DeepStarts.plan(this.seed, this.bounds, layout, DeepTerrain.forSeed(this.seed, this.bounds, layout),
				this.level.getServer().getStructureTemplateManager(), this.level.registryAccess(), vaultExitZ(this.level, layout))) {
				expected.put(plan.key(), plan.chunk());
				layers.put(plan.key(), plan.layer());
			}
			Object[][] projected = {{"deep_trail_ruins", DeepStarts.TRAIL_RUINS, 8600L}, {"deep_abandoned_camp_dappled", DeepStarts.CAMP_DAPPLED, 8700L},
				{"deep_abandoned_camp_pale", DeepStarts.CAMP_PALE, 8800L}, {"deep_pillager_outpost", DeepStarts.OUTPOST, 8900L}};
			for (int l = 1; l <= layout.deepLayers; l++) {
				for (Object[] p : projected) {
					if (!layout.layer(l).extraStructures().contains((String) p[0])) continue;
					@SuppressWarnings("unchecked")
					var key = (net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>) p[1];
					expected.put(key, DeepStarts.projectedChunk(this.seed, this.bounds, layout, l, (Long) p[2]));
					layers.put(key, l);
				}
				if (layout.layer(l).mineshaft() != LayerSpec.Mineshaft.NONE) {
					expected.put(DeepStarts.mineshaft(l), DeepStarts.mineshaftChunk(this.seed, this.bounds, l));
					layers.put(DeepStarts.mineshaft(l), l);
				}
			}
			expected.put(VaultPlan.KEY, generator.vaultChunk(this.seed));
			layers.put(VaultPlan.KEY, layout.vaultLayer);
			for (var e : expected.entrySet()) {
				var structure = structures.getValue(e.getKey());
				String path = e.getKey().identifier().getPath();
				if (structure == null) {
					bad.add(path + " missing from the registry");
					continue;
				}
				// the start chunk (and every other strip chunk loaded) must hold it exactly once
				int count = 0;
				StructureStart found = null;
				for (LevelChunk chunk : this.stripChunks()) {
					StructureStart start = chunk.getStartForStructure(structure);
					if (start != null && start.isValid()) {
						count++;
						found = start;
					}
				}
				if (this.chunk(e.getValue().x(), e.getValue().z()) == null) {
					good.add(path + " (chunk not loaded)");
					continue;
				}
				if (count != 1 || found == null || !found.getChunkPos().equals(e.getValue())) {
					bad.add(path + " x" + count);
					continue;
				}
				int layer = layers.get(e.getKey());
				int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
				for (var piece : found.getPieces()) {
					minY = Math.min(minY, piece.getBoundingBox().minY());
					maxY = Math.max(maxY, piece.getBoundingBox().maxY());
				}
				if (layout.layerAt(minY) != layer || layout.layerAt(maxY) != layer) bad.add(path + " at y " + minY + ".." + maxY + " (not in layer " + layer + ")");
				else good.add(path + " y " + minY + ".." + maxY);
			}
			this.report("deep starts", bad.isEmpty(), expected.size() + " expected; " + (bad.isEmpty() ? "" : "bad " + bad + "; ") + "ok " + good);
		}

		private static @Nullable Integer vaultExitZ(ServerLevel level, StripLayout layout) {
			if (layout.vaultLayer < 1) return null;
			VaultPlan plan = VaultPlan.get(level);
			return plan == null ? null : plan.ez;
		}

		/** The Trial Vault: its plan, the two holes cut where the plan says, the entrance well and the exit well with its nook. */
		private void checkVault(StripLayout layout) {
			VaultPlan plan = VaultPlan.get(this.level);
			if (plan == null) {
				this.report("trial vault", false, "no vault start");
				return;
			}
			int k6 = layout.vaultLayer - 1, k7 = layout.vaultLayer;
			BlockState in = this.block(plan.nx, layout.floorTop(k6), plan.nz), out = this.block(plan.ex, layout.floorTop(k7), plan.ez);
			BlockState well = this.block(plan.nx, layout.floorBase(k6) - 4, plan.nz);
			if (in == null || out == null) {
				this.skip("trial vault", plan + " (hole chunks not loaded)");
				return;
			}
			boolean ok = plan.entranceValid && in.is(Blocks.LADDER) && out.is(Blocks.LADDER) && (well == null || well.is(Blocks.SCAFFOLDING));
			boolean overStart = plan.nx >= plan.startBox.minX() && plan.nx <= plan.startBox.maxX() && plan.nz >= plan.startBox.minZ() && plan.nz <= plan.startBox.maxZ();
			boolean inRoom = plan.ex >= plan.exitRoom.minX() && plan.ex <= plan.exitRoom.maxX() && plan.ez >= plan.exitRoom.minZ() && plan.ez <= plan.exitRoom.maxZ();
			String exit = this.exitWell(layout, plan);
			this.report("trial vault", ok && overStart && inRoom && exit.startsWith("ok"), plan + "; floor " + k6 + " hole " + name(in) + ", floor " + k7 + " hole " + name(out)
				+ ", entrance well top " + name(well) + ", exit well " + exit + (plan.fallback ? " (FALLBACK exit)" : ""));
		}

		/**
		 * The exit well: a scaffolding column next to the floor-7 hole standing on the floor-7 bedrock
		 * and reaching up to free space in the room, and the nook (two free blocks) over the hole.
		 */
		private String exitWell(StripLayout layout, VaultPlan plan) {
			int bottom = layout.layerBottom(layout.vaultLayer);
			BlockState n0 = this.block(plan.ex, bottom, plan.ez), n1 = this.block(plan.ex, bottom + 1, plan.ez);
			if (n0 == null || n1 == null) return "ok? (not loaded)";
			if (!n0.isAir() || !n1.isAir()) return "BAD nook " + name(n0) + "/" + name(n1);
			int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] o : offsets) {
				int x = plan.ex + o[0], z = plan.ez + o[1];
				BlockState foot = this.block(x, bottom, z), under = this.block(x, bottom - 1, z);
				if (foot == null || !foot.is(Blocks.SCAFFOLDING) || under == null || !under.is(Blocks.BEDROCK)) continue;
				int y = bottom;
				while (y < plan.exitRoom.maxY()) {
					BlockState s = this.block(x, y + 1, z);
					if (s == null || !s.is(Blocks.SCAFFOLDING)) break;
					y++;
				}
				BlockState a1 = this.block(x, y + 1, z), a2 = this.block(x, y + 2, z);
				boolean open = a1 != null && a2 != null && a1.getCollisionShape(this.level, new BlockPos(x, y + 1, z)).isEmpty()
					&& a2.getCollisionShape(this.level, new BlockPos(x, y + 2, z)).isEmpty();
				return (open ? "ok" : "BAD (blocked top)") + " at (" + x + " " + z + ") " + bottom + ".." + y + " (" + (y - bottom + 1) + " long)";
			}
			return "BAD (no scaffolding column on bedrock next to the hole)";
		}

		/** Every village start in the loaded strip chunks has a farm piece. */
		private void checkVillages(StripLayout layout, List<LevelChunk> strip) {
			int villages = 0;
			List<String> noFarm = new ArrayList<>();
			for (LevelChunk chunk : strip) {
				for (StructureStart start : chunk.getAllStarts().values()) {
					if (!VillageFarms.isVillage(start)) continue;
					villages++;
					if (!VillageFarms.hasFarm(start)) noFarm.add(start.getChunkPos() + " y " + start.getBoundingBox().minY());
				}
			}
			if (villages == 0) {
				this.skip("village farms", "no village start loaded");
				return;
			}
			this.report("village farms", noFarm.isEmpty(), villages + " villages, without a farm: " + noFarm);
		}

		// ------------------------------------------------------------------ floors and seals

		/** Hole of floor k (the Trial Vault's two floors from its plan), or null if the plan is not available. */
		private int @Nullable [] hole(StripLayout layout, int k) {
			if (layout.vaultLayer >= 1 && (k == layout.vaultLayer - 1 || k == layout.vaultLayer)) {
				VaultPlan plan = VaultPlan.get(this.level);
				return plan == null ? null : new int[] {plan.holeX(layout, k), plan.holeZ(layout, k)};
			}
			return new int[] {layout.holeX(this.seed, k, this.bounds), layout.holeZ(this.seed, k, this.bounds)};
		}

		private void checkFloor(StripLayout layout, int k) {
			int[] h = this.hole(layout, k);
			if (h == null) {
				this.skip("floor " + k, "no trial vault plan");
				return;
			}
			int hx = h[0], hz = h[1];
			int base = layout.floorBase(k), top = layout.floorTop(k);
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

		private void checkSeal(StripLayout layout, int k) {
			String name = "seal " + k;
			int[] h = this.hole(layout, k);
			if (h == null) {
				this.skip(name, "no trial vault plan");
				return;
			}
			int hx = h[0], hz = h[1], top = layout.floorTop(k);
			BlockState state = this.block(hx, top, hz);
			if (state == null) {
				this.skip(name, "hole chunk not loaded");
				return;
			}
			AntCraftConfig config = AntCraftConfig.get();
			Block seal = k == layout.sealedFloor ? ModContent.NETHER_SEAL
				: k == layout.ancientSealedFloor ? ModContent.ANCIENT_SEAL
				: k == layout.emberSealedFloor ? ModContent.EMBER_SEAL
				: layout.isGuardianFloor(k) ? ModContent.GUARDIAN_SEAL : null;
			boolean gateOn = k == layout.sealedFloor ? config.netherKeyGate
				: k == layout.ancientSealedFloor ? config.ancientKeyGate
				: k == layout.emberSealedFloor ? config.emberKeyGate
				: layout.isGuardianFloor(k) && config.guardians;
			String where = "(" + hx + " " + top + " " + hz + ") " + name(state);
			if (seal == null) {
				this.report(name, state.is(Blocks.LADDER), where + ", expected an open ladder");
			} else if (state.is(seal)) {
				this.report(name, true, where + (gateOn ? "" : " (gate is off in the config now; floors generated while it was on stay sealed)"));
			} else if (state.is(Blocks.LADDER)) {
				if (layout.isGuardianFloor(k) && gateOn) {
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

		private void checkBossRoom(StripLayout layout, int k) {
			String name = "boss room " + k;
			int hx = layout.holeX(this.seed, k, this.bounds), hz = layout.holeZ(this.seed, k, this.bounds), floorTop = layout.floorTop(k);
			BlockState hole = this.block(hx, floorTop, hz);
			boolean expected = hole != null && hole.is(ModContent.GUARDIAN_SEAL)
				|| (hole == null || hole.is(Blocks.LADDER)) && AntCraftConfig.get().guardians;
			if (!expected) {
				this.skip(name, "guardians are off and the hole is open");
				return;
			}
			boolean fighting = ArenaManager.state(this.level.getServer().overworld(), k) == ArenaManager.FIGHTING;
			int top = floorTop + layout.hallHeight + ArenaManager.WALL_EXTRA_HEIGHT;
			Problems bad = new Problems();
			int checked = 0;
			for (int side = -1; side <= 1; side += 2) {
				int z = ArenaManager.wallZ(layout, this.seed, k, this.bounds, side);
				if (z < this.bounds.minZ() || z > this.bounds.maxZ() || this.chunk(this.bounds.minX() >> 4, z >> 4) == null) continue;
				for (int x = this.bounds.minX(); x <= this.bounds.maxX(); x++) {
					for (int y = floorTop + 1; y <= top; y++) {
						BlockState state = this.block(x, y, z);
						if (state == null) continue;
						checked++;
						boolean ok = ArenaManager.isDoorway(layout, this.bounds, k, x, y)
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
		private void checkLeaves(StripLayout layout, List<LevelChunk> strip) {
			Problems bad = new Problems();
			int leaves = 0;
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			for (LevelChunk chunk : strip) {
				ChunkPos pos = chunk.getPos();
				for (int layer = 1; layer <= layout.deepLayers; layer++) {
					int y0 = Math.max(layout.forestFloor(layer) - DeepTerrain.LEAF_BAND_BELOW, layout.layerBottom(layer));
					int y1 = Math.min(layout.forestFloor(layer) + DeepTerrain.leafBandAbove(layout), layout.layerTop(layer));
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

		private void checkPortalRoom(StripLayout layout) {
			int cz = layout.portalRoomZ(this.seed, this.bounds);
			int minX = PortalRoom.minX(this.bounds), maxX = PortalRoom.maxX(this.bounds), px = PortalRoom.portalX(this.bounds);
			int minZ = cz - PortalRoom.HALF_LENGTH, maxZ = cz + PortalRoom.HALF_LENGTH - 1;
			int floorY = layout.portalRoomFloorY(), ceilY = floorY + PortalRoom.HEIGHT, fy = floorY + 2;
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
			this.expect(bad, PortalRoom.spawnerPos(this.seed, this.bounds, layout), Blocks.SPAWNER);
			this.expect(bad, PortalRoom.chestA(this.seed, this.bounds, layout), Blocks.CHEST);
			this.expect(bad, PortalRoom.chestB(this.seed, this.bounds, layout), Blocks.CHEST);
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
