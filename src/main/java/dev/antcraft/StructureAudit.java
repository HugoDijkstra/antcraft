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
}
