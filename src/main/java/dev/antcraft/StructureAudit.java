package dev.antcraft;

import com.mojang.brigadier.context.CommandContext;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * {@code /antcraft audit structures} (ops, only with {@code -Dantcraft.dev=true}): logs every valid
 * structure start in the loaded strip chunks of each strip dimension, with its start box, piece
 * count and the y range its pieces cover. Used to check the tall layouts' structure heights.
 */
final class StructureAudit {
	private StructureAudit() {}

	static boolean enabled() {
		return Boolean.getBoolean("antcraft.dev");
	}

	static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int total = 0;
		for (ServerLevel level : source.getServer().getAllLevels()) {
			StripBounds bounds = Strip.bounds(level);
			if (bounds == null) continue;
			var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			Map<String, String> lines = new TreeMap<>();
			for (int cx = (bounds.minX() >> 4) - 1; cx <= (bounds.maxX() >> 4) + 1; cx++) {
				for (int cz = (bounds.minZ() >> 4) - 1; cz <= (bounds.maxZ() >> 4) + 1; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
						StructureStart start = e.getValue();
						if (!start.isValid()) continue;
						Identifier id = registry.getKey(e.getKey());
						BoundingBox box = start.getBoundingBox();
						int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
						for (StructurePiece piece : start.getPieces()) {
							minY = Math.min(minY, piece.getBoundingBox().minY());
							maxY = Math.max(maxY, piece.getBoundingBox().maxY());
						}
						String key = id + " @" + cx + "," + cz;
						lines.put(key, "audit " + level.dimension().identifier().getPath() + " " + key + " box x " + box.minX() + ".." + box.maxX() + " y "
							+ box.minY() + ".." + box.maxY() + " z " + box.minZ() + ".." + box.maxZ() + " pieces " + start.getPieces().size() + " piece-y " + minY + ".." + maxY
							+ " first " + start.getPieces().getFirst().getType() + " " + start.getPieces().getFirst().getBoundingBox());
					}
				}
			}
			for (String line : lines.values()) AntCraft.LOGGER.info(line);
			total += lines.size();
		}
		int n = total;
		source.sendSuccess(() -> Component.literal("AntCraft audit: " + n + " structure starts logged"), false);
		AntCraft.LOGGER.info("audit done: {} starts", n);
		return n;
	}

	/**
	 * {@code /antcraft audit spawns}: for the tall Nether, samples the strip (every 8 z on the centre
	 * line, five heights per layer, in loaded chunks) and logs per layer how often each natural
	 * MONSTER list ({@code getMobsAt}, structure overrides included) comes back.
	 */
	static int spawns(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
		if (level == null || !(level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator generator) || !generator.netherLayout().tall()) {
			source.sendFailure(Component.literal("no tall strip Nether in this world"));
			return 0;
		}
		StripBounds bounds = Strip.bounds(level);
		NetherLayout layout = generator.netherLayout();
		var registry = level.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE);
		int lines = 0;
		for (int layer = 0; layer <= layout.deepLayers; layer++) {
			int bottom = layer == 0 ? layout.floorBase(0) + 46 : layout.layerBottom(layer), top = layer == 0 ? layout.floorBase(0) + 110 : layout.layerTop(layer);
			int[] ys = layer == 0 ? new int[] {bottom, (bottom + top) / 2, top} : new int[] {bottom + 2, layout.lavaTop(layer) + 4, bottom + 50, (bottom + top) / 2, top - 6};
			Map<String, Integer> counts = new TreeMap<>();
			for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 8) {
				if (level.getChunkSource().getChunkNow(bounds.centerX() >> 4, z >> 4) == null) continue;
				for (int y : ys) {
					net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(bounds.centerX(), y, z);
					var list = generator.getMobsAt(level, level.structureManager(), net.minecraft.world.entity.MobCategory.MONSTER, pos);
					StringBuilder key = new StringBuilder("[");
					for (var weighted : list.unwrap()) {
						if (key.length() > 1) key.append(' ');
						key.append(registry.getKey(weighted.value().type()).getPath()).append(':').append(weighted.weight());
					}
					counts.merge(key.append(']').toString(), 1, Integer::sum);
				}
			}
			String line = "audit spawns " + (layer == 0 ? "band" : "N" + layer) + " " + counts;
			AntCraft.LOGGER.info(line);
			lines++;
		}
		int n = lines;
		source.sendSuccess(() -> Component.literal("AntCraft audit: " + n + " spawn lines logged"), false);
		return n;
	}

	/**
	 * {@code /antcraft audit vault <count> [width length]} (spec 3.5.6 tuning): grows {@code count}
	 * vault tries (seeds mix(level seed, 9500 + i), as best-of-N uses them, then beyond) for a strip
	 * of the given size (default: this world's) without placing anything, and logs per try the
	 * pieces, chamber rooms, the y extent above and below the start piece and the exit-well length.
	 */
	static int vault(CommandContext<CommandSourceStack> context, int width, int length) {
		CommandSourceStack source = context.getSource();
		int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count");
		ServerLevel level = source.getServer().overworld();
		if (!(level.getChunkSource().getGenerator() instanceof StripChunkGenerator generator) || generator.layout().vaultLayer < 1) {
			source.sendFailure(Component.literal("no Trial Vault in this world"));
			return 0;
		}
		StripBounds bounds = width > 0 ? StripBounds.of(width, length) : generator.bounds();
		int margin = StripChunkGenerator.vaultMargin(bounds);
		var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(VaultPlan.KEY).orElseThrow();
		StripLayout layout = generator.layout();
		int bottom = layout.layerBottom(layout.vaultLayer);
		int rooms4 = 0, both = 0, valid = 0, fallback = 0;
		java.util.List<Integer> roomCounts = new java.util.ArrayList<>();
		long t0 = System.nanoTime();
		for (int i = 0; i < count; i++) {
			StructureStart start = generator.vaultTry(holder, level.registryAccess(), level.getChunkSource().getGeneratorState(), source.getServer().getStructureTemplateManager(),
				level.dimension(), Strip.mix(level.getSeed(), 9500 + i), bounds, margin, StripChunkGenerator.vaultRotation(i));
			VaultPlan plan = start == null ? null : VaultPlan.of(start, bounds);
			if (plan == null) {
				AntCraft.LOGGER.info("vaultaudit {}x{} M={} try {}: invalid", bounds.width(), bounds.length(), margin, i);
				continue;
			}
			valid++;
			if (i < Integer.getInteger("antcraft.vaultDump", 0)) {
				for (StructurePiece piece : start.getPieces()) AntCraft.LOGGER.info("vaultdump {} {} {}", i, VaultPlan.location(piece), piece.getBoundingBox());
			}
			roomCounts.add(plan.chamberRooms);
			if (plan.chamberRooms >= 4) rooms4++;
			if (plan.growsBothWays()) both++;
			if (plan.fallback) fallback++;
			int touchTop = plan.maxY() >= layout.layerTop(layout.vaultLayer) - 6 ? 1 : 0, touchBottom = plan.minY() <= bottom + 2 ? 1 : 0;
			AntCraft.LOGGER.info("vaultaudit {}x{} M={} try {}: pieces {} rooms {} above {} below {} touchTop {} touchBottom {} grade {} entrance {} exitWell {} fallback {} entranceWell {}",
				bounds.width(), bounds.length(), margin, i, plan.pieces.size(), plan.chamberRooms, plan.maxY() - plan.startBox.maxY(), plan.startBox.minY() - plan.minY(),
				touchTop, touchBottom, VaultPlan.grade(plan), plan.entranceValid, plan.exitRoom.minY() - bottom, plan.fallback,
				plan.entranceValid ? layout.floorBase(layout.vaultLayer - 1) - 4 - plan.entranceFloorY : -1);
		}
		java.util.Collections.sort(roomCounts);
		String summary = "vaultaudit summary " + bounds.width() + "x" + bounds.length() + " M=" + margin + ": " + valid + "/" + count + " valid, >=4 rooms " + rooms4
			+ ", grows both ways " + both + ", fallback " + fallback + ", median rooms " + (roomCounts.isEmpty() ? 0 : roomCounts.get(roomCounts.size() / 2)) + ", "
			+ (System.nanoTime() - t0) / 1_000_000 / Math.max(1, count) + " ms/try";
		AntCraft.LOGGER.info(summary);
		source.sendSuccess(() -> Component.literal(summary), false);
		return valid;
	}
}
