package dev.antcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AntCraft implements ModInitializer {
	public static final String MOD_ID = "antcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		AntCraftConfig.load();
		AutoUpdater.start();
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath(MOD_ID, "strip"), StripChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath(MOD_ID, "nether_strip"), NetherStripChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath(MOD_ID, "end_strip"), EndStripChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(MOD_ID, "strip"), StripBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(MOD_ID, "nether_strip"), NetherStripBiomeSource.CODEC);
		ModContent.register();
		WorldUpgrade.register();
		MiningHardness.register();
		LayerTracker.register();
		ArenaManager.register();
		SelfCheck.register();
		TallAdvancements.register();

		// Backup source of the Nether Key: fortress chests (the shrine in the Nether strip always has one).
		// Added when the loot is rolled rather than to the table, so vanilla worlds don't get the key.
		// Not tied to netherKeyGate: floors generated while the gate was on stay sealed.
		LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
			if (table.is(BuiltInLootTables.NETHER_BRIDGE) && isStripLevel(context.getLevel()) && context.getRandom().nextFloat() < 0.5F) {
				drops.add(new ItemStack(ModContent.NETHER_KEY));
			}
			// the Ancient Key for floor 7's seal: in every ancient city chest of a strip world
			if (table.is(BuiltInLootTables.ANCIENT_CITY) && isStripLevel(context.getLevel())) {
				drops.add(new ItemStack(ModContent.ANCIENT_KEY));
			}
		});

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			FrameTerrain.setFallbackFactory(server.registryAccess());
			VaultPlan.loadTable(server.getStructureTemplateManager());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VaultPlan.clearRuntimeCache());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register((server, level) -> checkLayoutHeight(level));
		ServerLifecycleEvents.SERVER_STARTED.register(AntCraft::onServerStarted);
		ServerTickEvents.END_SERVER_TICK.register(AntCraft::onServerTick);
	}

	/** The strip overworld, Nether or End (any dimension whose generator is a strip). */
	/** Crop growth multiplier in the deep layers (no sun down there). */
	public static final float DEEP_CROP_GROWTH = 3.0F;

	/** True for a position in a deep layer (below the vanilla world) of the strip overworld. */
	public static boolean isDeepFarm(ServerLevel level, BlockPos pos) {
		if (level.dimension() != Level.OVERWORLD) return false;
		StripLayout layout = Strip.layout(level);
		return layout != null && pos.getY() < layout.vanillaMinY;
	}

	public static boolean isStripLevel(ServerLevel level) {
		return level.getChunkSource().getGenerator() instanceof StripGenerator;
	}

	/**
	 * A strip overworld's layout needs the matching dimension type (its height). A world whose
	 * level.dat pairs them wrongly would generate broken terrain: refuse to load it.
	 */
	private static void checkLayoutHeight(ServerLevel level) {
		if (level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator nether) {
			NetherLayout n = nether.netherLayout();
			if (n.tall() && (level.getMinY() != n.worldMinY || level.getMaxY() != n.worldMaxY)) {
				throw new IllegalStateException("AntCraft Nether layout " + n.id + " needs dimension type antcraft:strip_nether_tall (y " + n.worldMinY + ".." + n.worldMaxY
					+ "), but " + level.dimension().identifier() + " has y " + level.getMinY() + ".." + level.getMaxY());
			}
			return;
		}
		StripLayout layout = Strip.layout(level);
		if (layout == null) return;
		if (level.getMinY() != layout.worldMinY || level.getMaxY() != layout.worldMaxY) {
			throw new IllegalStateException("AntCraft layout " + layout.id + " needs dimension type " + (layout.id >= 2 ? Strip.TALL_OVERWORLD_TYPE.identifier() : "antcraft:strip_overworld")
				+ " (y " + layout.worldMinY + ".." + layout.worldMaxY + "), but " + level.dimension().identifier() + " has y " + level.getMinY() + ".." + level.getMaxY());
		}
	}

	/** Makes sure the world spawn is on dry land inside the strip. */
	private static void onServerStarted(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		if (!isStripLevel(overworld)) return;
		long seed = overworld.getSeed();
		StripBounds bounds = Strip.bounds(overworld);
		StripLayout layout = Strip.layout(overworld);
		if (layout != null) {
			LOGGER.info("AntCraft strip world: {} x {} strip {}, layout {}, holes at {}", bounds.width(), bounds.length(), bounds, layout.id, describeHoles(overworld, layout, seed, bounds));
			if (layout.vaultLayer >= 1) LOGGER.info("AntCraft: trial vault {}", VaultPlan.get(overworld));
		}

		// Respawning picks a random spot within this radius; outside the strip there is only void (and wall tops).
		server.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);

		BlockPos spawn = overworld.getRespawnData().pos();
		if (!bounds.inside(spawn.getX(), spawn.getZ()) || !isSafe(overworld, spawn)) {
			spawn = relocateSpawn(overworld, bounds);
		}
		placeStarterChest(server, overworld, spawn, bounds);
	}

	private static BlockPos relocateSpawn(ServerLevel overworld, StripBounds bounds) {
		int x = bounds.centerX(), z = bounds.centerZ();
		int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos ground = new BlockPos(x, y - 1, z);
		if (!overworld.getFluidState(ground).isEmpty() || overworld.getBlockState(ground).isAir()) {
			// ocean or void: build a small grass island
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					overworld.setBlockAndUpdate(ground.offset(dx, 0, dz), Blocks.GRASS_BLOCK.defaultBlockState());
					for (int dy = 1; dy <= 3; dy++) overworld.setBlockAndUpdate(ground.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
		BlockPos newSpawn = ground.above();
		overworld.setRespawnData(LevelData.RespawnData.of(overworld.dimension(), newSpawn, 0.0F, 0.0F));
		LOGGER.info("AntCraft: world spawn moved to {}", newSpawn);
		return newSpawn;
	}

	/**
	 * Only a narrow slice of the surface exists, so a world may have no trees at all up top (or be
	 * all ocean). Once per world, put a chest with wood, saplings and food next to spawn.
	 */
	private static void placeStarterChest(MinecraftServer server, ServerLevel overworld, BlockPos spawn, StripBounds bounds) {
		Path marker = server.getWorldPath(LevelResource.ROOT).resolve("antcraft_starter_chest");
		if (Files.exists(marker)) return;
		if (overworld.getGameTime() > 200) {
			// a world that was already played before this feature existed: don't touch it
			writeMarker(marker, "skipped, existing world");
			return;
		}
		// two blocks from spawn, towards the middle of the strip, on top of the ground
		int dx = spawn.getX() <= bounds.centerX() ? 2 : -2;
		int x = spawn.getX() + dx, z = spawn.getZ();
		int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos chest = new BlockPos(x, Math.max(y, spawn.getY()), z);
		BlockState below = overworld.getBlockState(chest.below());
		if (!below.isFaceSturdy(overworld, chest.below(), net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) {
			overworld.setBlockAndUpdate(chest.below(), Blocks.GRASS_BLOCK.defaultBlockState());
		}
		if (overworld.getBlockState(chest.above()).isRedstoneConductor(overworld, chest.above())) {
			overworld.setBlockAndUpdate(chest.above(), Blocks.AIR.defaultBlockState()); // a solid block on top would stop it opening
		}
		overworld.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
		if (overworld.getBlockEntity(chest) instanceof ChestBlockEntity be) {
			be.setItem(0, new ItemStack(Items.OAK_LOG, 16));
			be.setItem(1, new ItemStack(Items.OAK_SAPLING, 6));
			be.setItem(2, new ItemStack(Items.BIRCH_SAPLING, 2));
			be.setItem(3, new ItemStack(Items.BREAD, 8));
			be.setItem(4, new ItemStack(Items.WHEAT_SEEDS, 4));
			be.setItem(5, new ItemStack(Items.TORCH, 8));
		}
		writeMarker(marker, "starter chest placed at " + chest);
		LOGGER.info("AntCraft: starter chest placed at {}", chest);
	}

	private static void writeMarker(Path marker, String text) {
		try {
			Files.writeString(marker, text + "\n");
		} catch (IOException e) {
			LOGGER.warn("AntCraft: could not write starter chest marker", e);
		}
	}

	private static boolean isSafe(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below();
		return level.getBlockState(below).isSolid() && level.getFluidState(below).isEmpty() && level.getBlockState(feet).isAir();
	}

	private static String describeHoles(ServerLevel level, StripLayout layout, long seed, StripBounds bounds) {
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < layout.floorCount; k++) {
			if (k > 0) sb.append(", ");
			sb.append('(').append(VaultPlan.holeX(level, layout, bounds, k)).append(' ').append(layout.floorTop(k)).append(' ').append(VaultPlan.holeZ(level, layout, bounds, k)).append(')');
		}
		return sb.toString();
	}

	/** Safety net: anyone who ends up outside the strip in a strip dimension is put back inside. */
	private static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		for (ServerLevel level : server.getAllLevels()) {
			StripBounds bounds = Strip.bounds(level);
			if (bounds != null) rescue(level, bounds);
		}
	}

	private static void rescue(ServerLevel level, StripBounds bounds) {
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) continue;
			BlockPos pos = player.blockPosition();
			if (bounds.inside(pos.getX(), pos.getZ())) continue;
			BlockPos target = findSafeInside(level, bounds.clampInside(pos, 1), bounds);
			player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
			player.fallDistance = 0;
		}
	}

	/** Nearest pair of free blocks above solid ground in the column, searching up then down from {@code start}. */
	private static BlockPos findSafeInside(ServerLevel level, BlockPos start, StripBounds bounds) {
		int startY = Math.clamp(start.getY(), level.getMinY() + 6, level.getMaxY() - 2);
		for (int d = 0; d < 128; d++) {
			for (int y : new int[] {startY + d, startY - d}) {
				if (y <= level.getMinY() || y >= level.getMaxY()) continue;
				BlockPos feet = new BlockPos(start.getX(), y, start.getZ());
				if (isSafe(level, feet) && level.getBlockState(feet.above()).isAir()) return feet;
			}
		}
		// nothing safe nearby: world spawn in the overworld, the key shrine's floor in the Nether,
		// the arrival platform (rebuilt if needed) in the End
		if (level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator nether) {
			return KeyShrine.chestPos(bounds, nether.layout()).south(2).below();
		}
		if (level.getChunkSource().getGenerator() instanceof EndStripChunkGenerator) {
			BlockPos spawn = EndStripChunkGenerator.spawnPoint(bounds);
			net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(level, spawn.below(), true);
			return spawn;
		}
		return level.getRespawnData().pos();
	}
}
