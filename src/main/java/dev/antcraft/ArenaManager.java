package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The boss rooms on the guardian floors of the layout ({@link StripLayout#guardianFloors}; floors
 * 0, 2 and 6 in layout 1). The floor hall around the
 * hole is closed off by two walls across the strip at hole z +-{@link #WALL_OFFSET}, each with a
 * 3-wide doorway ({@link DeepTerrain#cutHoles} builds them); the hole is covered by a Guardian Seal.
 *
 * <p>Every 10 ticks, per floor (state saved with the world):
 * <ul>
 *   <li><b>idle</b>: pressing the start button on the pedestal next to the hole closes the
 *       doorways with arena gates, spawns the guardian and shows a boss bar (&rarr; fighting);</li>
 *   <li><b>fighting</b>: the guardian dies &rarr; gates open, the seal becomes a ladder, the
 *       guardian advancement is granted (&rarr; cleared). Nobody alive left inside &rarr; the
 *       guardian vanishes and the gates open (&rarr; idle, full reset for the next try);</li>
 *   <li><b>cleared</b>: nothing happens any more.</li>
 * </ul>
 */
public final class ArenaManager {
	private ArenaManager() {}

	/** The walls stand this far from the hole (z); the room is everything between them. */
	public static final int WALL_OFFSET = 12;
	/** Doorway: 3 wide around the strip's centre x, 3 high. */
	public static final int DOOR_HALF_WIDTH = 1, DOOR_HEIGHT = 3;
	/** The walls go up through the hall ceiling, which is closed inside the room. */
	public static final int WALL_EXTRA_HEIGHT = 2;

	public static final String GUARDIAN_TAG = "antcraft.guardian";

	static final int IDLE = 0, FIGHTING = 1, CLEARED = 2;

	// ------------------------------------------------------------------ geometry

	/** z of the two walls (may lie outside the strip, then that side is closed by the strip's end wall). */
	public static int wallZ(ArenaLayout layout, long seed, int floor, StripBounds bounds, int side) {
		return layout.holeZ(seed, floor, bounds) + side * WALL_OFFSET;
	}

	public static boolean isDoorway(ArenaLayout layout, StripBounds bounds, int floor, int x, int y) {
		int floorTop = layout.floorTop(floor);
		return Math.abs(x - bounds.centerX()) <= DOOR_HALF_WIDTH && y > floorTop && y <= floorTop + DOOR_HEIGHT;
	}

	/** The room's inside, from the floor up to the hall ceiling. */
	public static AABB roomBox(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		int hz = layout.holeZ(seed, floor, bounds);
		int floorTop = layout.floorTop(floor);
		int minZ = Math.max(bounds.minZ(), hz - WALL_OFFSET + 1), maxZ = Math.min(bounds.maxZ(), hz + WALL_OFFSET - 1);
		return new AABB(bounds.minX(), floorTop + 1, minZ, bounds.maxX() + 1, floorTop + 1 + layout.hallHeight() + WALL_EXTRA_HEIGHT, maxZ + 1);
	}

	private static BlockPos holePos(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		return new BlockPos(layout.holeX(seed, floor, bounds), layout.floorTop(floor), layout.holeZ(seed, floor, bounds));
	}

	/**
	 * The start button: on a pedestal 3 blocks from the hole along z (towards the strip's middle
	 * if the hole is near an end), clear of the hole's lanterns.
	 */
	static BlockPos buttonPos(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		int hx = layout.holeX(seed, floor, bounds), hz = layout.holeZ(seed, floor, bounds);
		int z = hz + 3 <= bounds.maxZ() - 1 ? hz + 3 : hz - 3;
		return new BlockPos(hx, layout.floorTop(floor) + 2, z);
	}

	/** The teleporter pad: 3 blocks from the hole across the strip (towards its middle), on the floor. */
	static BlockPos teleporterPos(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		int hx = layout.holeX(seed, floor, bounds), hz = layout.holeZ(seed, floor, bounds);
		return new BlockPos(hx < bounds.centerX() ? hx + 3 : hx - 3, layout.floorTop(floor) + 1, hz);
	}

	private static List<BlockPos> gatePositions(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		List<BlockPos> list = new ArrayList<>();
		int floorTop = layout.floorTop(floor);
		for (int side = -1; side <= 1; side += 2) {
			int z = wallZ(layout, seed, floor, bounds, side);
			if (z < bounds.minZ() || z > bounds.maxZ()) continue;
			for (int x = bounds.centerX() - DOOR_HALF_WIDTH; x <= bounds.centerX() + DOOR_HALF_WIDTH; x++) {
				for (int y = floorTop + 1; y <= floorTop + DOOR_HEIGHT; y++) list.add(new BlockPos(x, y, z));
			}
		}
		return list;
	}

	// ------------------------------------------------------------------ guardians

	private static Component guardianName(ArenaLayout layout, int floor) {
		GuardianId g = layout.guardian(floor);
		return g == null ? Component.translatableWithFallback("antcraft.guardian." + floor, "Guardian") : Component.translatableWithFallback(g.langKey, g.englishName);
	}

	private static @Nullable Mob spawnGuardian(ServerLevel level, ArenaLayout layout, int floor, BlockPos hole) {
		GuardianId g = layout.guardian(floor);
		if (g == null) return null;
		Mob mob = g.entityType().create(level, EntitySpawnReason.EVENT);
		if (mob == null) return null;
		mob.snapTo(hole.getX() + 0.5, hole.getY() + 1, hole.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
		setBase(mob, Attributes.MAX_HEALTH, g.health(layout.arenaLayoutId()));
		if (g.scale != 1.0) setBase(mob, Attributes.SCALE, g.scale);
		setBase(mob, Attributes.FOLLOW_RANGE, 40.0);
		setBase(mob, Attributes.KNOCKBACK_RESISTANCE, g.knockbackResistance);
		mob.setHealth(mob.getMaxHealth());
		mob.setCustomName(guardianName(layout, floor));
		mob.setCustomNameVisible(true);
		mob.setPersistenceRequired();
		mob.addTag(GUARDIAN_TAG);
		g.outfit(mob, layout.arenaLayoutId());
		level.addFreshEntity(mob);
		return mob;
	}

	private static void setBase(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
		AttributeInstance instance = entity.getAttribute(attribute);
		if (instance != null) instance.setBaseValue(value);
	}

	// ------------------------------------------------------------------ state

	/** One boss room's state. {@code dimension}: the strip overworld for every room saved before the tall Nether existed. */
	private record Arena(ResourceKey<Level> dimension, int floor, int state, Optional<UUID> guardian) {
		static final Codec<Arena> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("dimension", Level.OVERWORLD).forGetter(Arena::dimension),
					Codec.INT.fieldOf("floor").forGetter(Arena::floor),
					Codec.INT.fieldOf("state").forGetter(Arena::state),
					UUIDUtil.CODEC.optionalFieldOf("guardian").forGetter(Arena::guardian)
				)
				.apply(i, Arena::new)
		);
	}

	private record RoomKey(ResourceKey<Level> dimension, int floor) {}

	/** Per-world arena states, in the overworld's data storage ({@code data/antcraft_arenas.dat}). */
	public static final class Data extends SavedData {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Arena.CODEC.listOf().fieldOf("arenas").forGetter(data -> List.copyOf(data.arenas.values())),
					BlockPos.CODEC.optionalFieldOf("surface_teleporter").forGetter(data -> Optional.ofNullable(data.surfaceTeleporter))
				)
				.apply(i, Data::new)
		);
		static final SavedDataType<Data> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "arenas"), Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE
		);

		private final Map<RoomKey, Arena> arenas = new java.util.LinkedHashMap<>();
		/** Set by {@link #data} before each use: the dimension the plain floor numbers refer to. */
		private ResourceKey<Level> dimension = Level.OVERWORLD;

		/** The overworld surface teleporter, once placed (after the first overworld guardian is defeated). */
		private @Nullable BlockPos surfaceTeleporter;

		Data() {}

		Data(List<Arena> list, Optional<BlockPos> surfaceTeleporter) {
			for (Arena arena : list) this.arenas.put(new RoomKey(arena.dimension(), arena.floor()), arena);
			this.surfaceTeleporter = surfaceTeleporter.orElse(null);
		}

		Arena get(int floor) {
			return this.arenas.getOrDefault(new RoomKey(this.dimension, floor), new Arena(this.dimension, floor, IDLE, Optional.empty()));
		}

		void set(int floor, int state, @Nullable UUID guardian) {
			this.arenas.put(new RoomKey(this.dimension, floor), new Arena(this.dimension, floor, state, Optional.ofNullable(guardian)));
			this.setDirty();
		}
	}

	/** The arena states (kept in the overworld's data storage), seen from a level's dimension. */
	private static Data data(ServerLevel level) {
		Data data = level.getServer().overworld().getDataStorage().computeIfAbsent(Data.TYPE);
		data.dimension = level.dimension();
		return data;
	}

	/** {@link #IDLE}, {@link #FIGHTING} or {@link #CLEARED} (for {@link SelfCheck}). */
	static int state(ServerLevel level, int floor) {
		return data(level).get(floor).state();
	}

	/** Boss bars of running fights (not saved; recreated after a restart). */
	private static final Map<RoomKey, ServerBossEvent> BARS = new HashMap<>();

	/**
	 * The layout whose boss rooms a level has: the strip overworld's, or the tall Nether's (layout
	 * 3); null for every other level.
	 */
	public static @Nullable ArenaLayout arenaLayout(ServerLevel level) {
		if (level.dimension() == Level.OVERWORLD) return Strip.layout(level);
		if (level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator nether && nether.netherLayout().tall()) return nether.netherLayout();
		return null;
	}

	// ------------------------------------------------------------------ ticking

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaManager::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register(ArenaManager::onDeath);
		ServerLivingEntityEvents.MOB_CONVERSION.register(ArenaManager::onConversion);
		UseBlockCallback.EVENT.register(ArenaManager::onUseBlock);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BARS.values().forEach(ServerBossEvent::removeAllPlayers);
			BARS.clear();
			PENDING.clear();
			LAST_TRAVEL.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		if (!PENDING.isEmpty()) {
			List<Runnable> pending = List.copyOf(PENDING);
			PENDING.clear();
			pending.forEach(Runnable::run);
		}
		tick(server, server.overworld());
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether != null) tick(server, nether);
	}

	/** One level's boss rooms ({@code overworld}: the strip overworld or the tall Nether). */
	private static void tick(MinecraftServer server, ServerLevel overworld) {
		StripBounds bounds = Strip.bounds(overworld);
		ArenaLayout layout = arenaLayout(overworld);
		if (bounds == null || layout == null) return;
		boolean full = server.getTickCount() % 10 == 0;
		long seed = overworld.getSeed();
		if (full && overworld.dimension() == Level.OVERWORLD) tickSurfaceTeleporter(overworld, layout, bounds);
		for (int floor : layout.guardianFloors()) {
			Arena arena = data(overworld).get(floor);
			if (arena.state() == FIGHTING) {
				if (full) tickFight(overworld, layout, bounds, seed, arena);
				else updateBar(overworld, arena);
			} else if (arena.state() == IDLE && full) {
				tickIdle(overworld, layout, bounds, seed, floor);
			} else if (arena.state() == CLEARED && full) {
				tickCleared(overworld, layout, bounds, seed, floor);
			}
		}
	}

	private static List<ServerPlayer> playersIn(ServerLevel level, AABB box, boolean survivalOnly) {
		List<ServerPlayer> list = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (!player.isAlive() || player.isSpectator() || (survivalOnly && player.isCreative())) continue;
			if (box.contains(player.position())) list.add(player);
		}
		return list;
	}

	private static void tickIdle(ServerLevel level, ArenaLayout layout, StripBounds bounds, long seed, int floor) {
		AABB room = roomBox(layout, seed, floor, bounds);
		if (playersIn(level, room.inflate(4, 2, 12), false).isEmpty()) return;
		// gates left closed (a fight that was reset while the room was unloaded): open them
		openGates(level, layout, seed, floor, bounds);
		BlockPos hole = holePos(layout, seed, floor, bounds);
		if (!level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL)) return; // no boss room here (older world, or guardians were off)
		if (!AntCraftConfig.get().guardians) {
			// guardians were switched off after this floor was generated: open the way for a survival player
			if (!playersIn(level, room, true).isEmpty()) {
				SealBlock.open(level, hole);
				data(level).set(floor, CLEARED, null);
			}
			return;
		}
		// any player nearby (creative too) gets the button; pressing it starts the fight
		placeButton(level, layout, seed, floor, bounds);
		placeTeleporter(level, layout, seed, floor, bounds, false);
		decorate(level, layout, seed, floor, bounds);
	}

	/** The deepest cleared boss room of a level, or -1. */
	private static int deepestCleared(ServerLevel level, ArenaLayout layout) {
		int[] floors = layout.guardianFloors();
		for (int j = floors.length - 1; j >= 0; j--) if (data(level).get(floors[j]).state() == CLEARED) return floors[j];
		return -1;
	}

	/**
	 * Once an overworld guardian is defeated, a lit teleporter appears on the surface next to the
	 * world spawn (placed when that spot is loaded, then remembered). It takes players down to the
	 * deepest defeated guardian's room.
	 */
	private static void tickSurfaceTeleporter(ServerLevel level, ArenaLayout layout, StripBounds bounds) {
		if (deepestCleared(level, layout) < 0) return;
		Data data = data(level);
		BlockPos pos = data.surfaceTeleporter;
		if (pos == null) {
			BlockPos spawn = level.getRespawnData().pos();
			int x = spawn.getX() + 2 <= bounds.maxX() ? spawn.getX() + 2 : spawn.getX() - 2;
			int z = Math.clamp(spawn.getZ(), bounds.minZ(), bounds.maxZ());
			if (!level.isLoaded(new BlockPos(x, spawn.getY(), z))) return;
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			pos = new BlockPos(x, y, z);
			if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.above()).canBeReplaced()) return;
			data.surfaceTeleporter = pos;
			data.setDirty();
			AntCraft.LOGGER.info("AntCraft: surface teleporter placed at {}", pos.toShortString());
		}
		if (!level.isLoaded(pos)) return;
		BlockState state = level.getBlockState(pos);
		if (state.is(ModContent.TELEPORTER)) {
			if (!state.getValue(TeleporterBlock.LIT)) level.setBlockAndUpdate(pos, state.setValue(TeleporterBlock.LIT, true));
		} else if (state.canBeReplaced()) {
			level.setBlockAndUpdate(pos, ModContent.TELEPORTER.defaultBlockState().setValue(TeleporterBlock.LIT, true));
			level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.2F);
		}
	}

	/** Cleared rooms (also ones cleared before teleporters existed) get a lit teleporter when a player comes near. */
	private static void tickCleared(ServerLevel level, ArenaLayout layout, StripBounds bounds, long seed, int floor) {
		if (playersIn(level, roomBox(layout, seed, floor, bounds).inflate(4, 2, 12), false).isEmpty()) return;
		placeTeleporter(level, layout, seed, floor, bounds, true);
		decorate(level, layout, seed, floor, bounds);
	}

	// ------------------------------------------------------------------ decoration

	/** The chiseled block in a boss-room wall that marks the room as decorated (so it happens once). */
	private static BlockPos decorationMarker(ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		int z = wallZ(layout, seed, floor, bounds, -1);
		if (z < bounds.minZ()) z = wallZ(layout, seed, floor, bounds, 1);
		return new BlockPos(bounds.minX(), layout.floorTop(floor) + layout.hallHeight(), z);
	}

	/**
	 * Themed decoration of a boss room (pillars, a carpet between the doors, hanging lanterns,
	 * banners on the walls, cobwebs, a few floor accents). Placed once, only into air, and never
	 * around the hole (seal, lanterns, button, teleporter); also added to rooms generated before.
	 */
	private static void decorate(ServerLevel level, ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		GuardianId guardian = layout.guardian(floor);
		if (guardian == null) return;
		BlockPos marker = decorationMarker(layout, seed, floor, bounds);
		if (!level.isLoaded(marker) || !level.getBlockState(marker).is(Blocks.POLISHED_BLACKSTONE_BRICKS)) return; // done, or no room
		int floorTop = layout.floorTop(floor), ceilingY = floorTop + layout.hallHeight(); // last air row
		int hx = layout.holeX(seed, floor, bounds), hz = layout.holeZ(seed, floor, bounds);
		int zLo = Math.max(bounds.minZ(), hz - WALL_OFFSET + 1), zHi = Math.min(bounds.maxZ(), hz + WALL_OFFSET - 1);
		// every block of the room must be loaded, or the decoration would be cut off
		for (int z = zLo; z <= zHi; z += 8) if (!level.isLoaded(new BlockPos(bounds.minX(), floorTop, z)) || !level.isLoaded(new BlockPos(bounds.maxX(), floorTop, z))) return;
		if (!level.isLoaded(new BlockPos(bounds.minX(), floorTop, zHi)) || !level.isLoaded(new BlockPos(bounds.maxX(), floorTop, zHi))) return;
		GuardianId.Theme theme = guardian.theme();
		int center = bounds.centerX();

		// pillars along both side walls
		for (int x : new int[] {bounds.minX() + 1, bounds.maxX() - 1}) {
			for (int dz : new int[] {-8, -4, 4, 8}) {
				int z = hz + dz;
				if (z < zLo || z > zHi || nearHole(x, z, hx, hz)) continue;
				boolean clear = true;
				for (int y = floorTop + 1; y <= ceilingY; y++) clear &= level.getBlockState(new BlockPos(x, y, z)).isAir();
				if (!clear) continue;
				for (int y = floorTop + 1; y <= ceilingY; y++) {
					boolean band = y == floorTop + 1 || y == ceilingY;
					level.setBlockAndUpdate(new BlockPos(x, y, z), band ? theme.accentPillar() : theme.pillar());
				}
				// an accent at the pillar's foot, on the side facing the room
				int ax = x == bounds.minX() + 1 ? x + 1 : x - 1;
				BlockState accent = theme.accent();
				if (accent.hasProperty(BlockStateProperties.ROTATION_16)) accent = accent.setValue(BlockStateProperties.ROTATION_16, level.getRandom().nextInt(16));
				placeIfAir(level, new BlockPos(ax, floorTop + 1, z), accent);
				if (theme.candles()) placeIfAir(level, new BlockPos(ax, floorTop + 2, z), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3).setValue(BlockStateProperties.LIT, true));
			}
		}
		// carpet runner between the two doorways
		for (int z = zLo; z <= zHi; z++) {
			if (nearHole(center, z, hx, hz)) continue;
			placeIfAir(level, new BlockPos(center, floorTop + 1, z), theme.carpet());
		}
		// lanterns on chains from the ceiling
		for (int x : new int[] {bounds.minX() + 4, bounds.maxX() - 4}) {
			for (int dz : new int[] {-7, 0, 7}) {
				int z = hz + dz;
				if (z < zLo || z > zHi || nearHole(x, z, hx, hz)) continue;
				BlockPos chain = new BlockPos(x, ceilingY, z), lamp = chain.below();
				if (level.getBlockState(chain).isAir() && level.getBlockState(lamp).isAir()) {
					level.setBlockAndUpdate(chain, Blocks.IRON_CHAIN.defaultBlockState());
					level.setBlockAndUpdate(lamp, theme.lantern().setValue(BlockStateProperties.HANGING, true));
				}
			}
		}
		// banners on the inside of the boss-room walls, flanking the doorway
		for (int side = -1; side <= 1; side += 2) {
			int wall = hz + side * WALL_OFFSET;
			if (wall < bounds.minZ() || wall > bounds.maxZ()) continue;
			int z = wall - side;
			net.minecraft.core.Direction facing = side < 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
			for (int x : new int[] {center - 3, center + 3, bounds.minX() + 2, bounds.maxX() - 2}) {
				placeIfAir(level, new BlockPos(x, floorTop + 4, z), theme.banner().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
			}
		}
		// cobwebs in the upper corners
		for (int x : new int[] {bounds.minX(), bounds.maxX()}) {
			for (int z : new int[] {zLo, zHi}) {
				placeIfAir(level, new BlockPos(x, ceilingY, z), Blocks.COBWEB.defaultBlockState());
				placeIfAir(level, new BlockPos(x, ceilingY - 1, z), Blocks.COBWEB.defaultBlockState());
				placeIfAir(level, new BlockPos(x + (x == bounds.minX() ? 1 : -1), ceilingY, z), Blocks.COBWEB.defaultBlockState());
			}
		}
		if (theme.extraCobwebs()) {
			// the Brood Mother's nest: webs on every pillar top and in every corner, vines on the pillars
			for (int x : new int[] {bounds.minX() + 1, bounds.maxX() - 1}) {
				for (int dz : new int[] {-8, -4, 4, 8}) {
					int z = hz + dz;
					if (z < zLo || z > zHi || nearHole(x, z, hx, hz)) continue;
					BlockPos top = new BlockPos(x, ceilingY, z);
					if (!level.getBlockState(top).is(theme.accentPillar().getBlock())) continue;
					int inward = x == bounds.minX() + 1 ? 1 : -1;
					placeIfAir(level, new BlockPos(x + inward, ceilingY, z), Blocks.COBWEB.defaultBlockState());
					for (int side = -1; side <= 1; side += 2) {
						// vines hang on the pillar from the z side (the block north or south of it)
						net.minecraft.world.level.block.state.properties.BooleanProperty face = side < 0
							? net.minecraft.world.level.block.VineBlock.SOUTH : net.minecraft.world.level.block.VineBlock.NORTH;
						for (int y = floorTop + 2; y < ceilingY; y++) {
							placeIfAir(level, new BlockPos(x, y, z + side), Blocks.VINE.defaultBlockState().setValue(face, true));
						}
					}
				}
			}
			for (int x : new int[] {bounds.minX(), bounds.maxX()}) {
				for (int z : new int[] {zLo, zHi}) {
					placeIfAir(level, new BlockPos(x, floorTop + 1, z), Blocks.COBWEB.defaultBlockState());
					placeIfAir(level, new BlockPos(x + (x == bounds.minX() ? 1 : -1), ceilingY - 1, z), Blocks.COBWEB.defaultBlockState());
					placeIfAir(level, new BlockPos(x, ceilingY, z + (z == zLo ? 1 : -1)), Blocks.COBWEB.defaultBlockState());
				}
			}
		}
		level.setBlockAndUpdate(marker, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
	}

	private static boolean nearHole(int x, int z, int hx, int hz) {
		return Math.abs(x - hx) <= 3 && Math.abs(z - hz) <= 3;
	}

	private static void placeIfAir(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, state);
	}

	private static void placeTeleporter(ServerLevel level, ArenaLayout layout, long seed, int floor, StripBounds bounds, boolean lit) {
		BlockPos pos = teleporterPos(layout, seed, floor, bounds);
		if (!level.isLoaded(pos)) return;
		BlockState state = level.getBlockState(pos);
		if (state.is(ModContent.TELEPORTER)) {
			if (state.getValue(TeleporterBlock.LIT) != lit) level.setBlockAndUpdate(pos, state.setValue(TeleporterBlock.LIT, lit));
			return;
		}
		if (!state.canBeReplaced() || !level.getBlockState(pos.above()).canBeReplaced()) return;
		level.setBlockAndUpdate(pos, ModContent.TELEPORTER.defaultBlockState().setValue(TeleporterBlock.LIT, lit));
		if (lit) level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.2F);
	}

	/** How a teleporter is used: up one room, down one defeated room, or down to the deepest defeated room. */
	enum Travel { UP, DOWN, DEEPEST }

	/** Teleports queued by jumps and crouches, run at the end of the server tick (not inside movement handling). */
	private static final List<Runnable> PENDING = new ArrayList<>();
	/** Server tick of each player's last teleport, so arriving on a pad does not bounce straight on. */
	private static final Map<UUID, Integer> LAST_TRAVEL = new HashMap<>();

	/**
	 * Right-click on a lit teleporter: up (sneaking: down to the deepest defeated guardian). The
	 * surface teleporter always goes down to the deepest defeated guardian.
	 */
	static void teleportUp(ServerLevel level, ServerPlayer player, BlockPos from) {
		boolean surface = level.dimension() == Level.OVERWORLD && from.equals(data(level).surfaceTeleporter);
		travel(level, player, from, surface || player.isShiftKeyDown() ? Travel.DEEPEST : Travel.UP);
	}

	/** A player jumped: standing on a teleporter, they go up. */
	public static void onJump(ServerPlayer player) {
		padTravel(player, Travel.UP);
	}

	/** A player started crouching: standing on a teleporter, they go down one defeated room. */
	public static void onCrouch(ServerPlayer player) {
		padTravel(player, Travel.DOWN);
	}

	private static void padTravel(ServerPlayer player, Travel travel) {
		if (!(player.level() instanceof ServerLevel level) || player.isSpectator()) return;
		BlockPos pos = BlockPos.containing(player.getX(), player.getY() - 0.2, player.getZ());
		BlockState state = level.getBlockState(pos);
		if (!state.is(ModContent.TELEPORTER)) return;
		int now = level.getServer().getTickCount();
		Integer last = LAST_TRAVEL.get(player.getUUID());
		if (last != null && now >= last && now - last < 10) return;
		if (!state.getValue(TeleporterBlock.LIT)) {
			player.sendOverlayMessage(Component.translatableWithFallback(
				"block.antcraft.teleporter.inactive", "The teleporter wakes up when this room's guardian is defeated"
			));
			return;
		}
		PENDING.add(() -> {
			if (player.isRemoved() || player.level() != level) return;
			travel(level, player, pos, travel);
		});
	}

	/**
	 * Teleports a player from a lit teleporter: up to the boss room on the floor above (onto its
	 * teleporter; from the first boss room to the surface teleporter, or the spawn if there is none),
	 * or down to the next / the deepest boss room whose guardian is defeated.
	 */
	private static void travel(ServerLevel level, ServerPlayer player, BlockPos from, Travel travel) {
		StripBounds bounds = Strip.bounds(level);
		ArenaLayout layout = arenaLayout(level);
		if (bounds == null || layout == null) return;
		long seed = level.getSeed();
		int[] floors = layout.guardianFloors();
		BlockPos surfacePad = level.dimension() == Level.OVERWORLD ? data(level).surfaceTeleporter : null;
		int index = -2; // the pad's room in floors; -1 = the surface teleporter
		if (from.equals(surfacePad)) {
			index = -1;
		} else {
			for (int i = 0; i < floors.length; i++) if (from.equals(teleporterPos(layout, seed, floors[i], bounds))) index = i;
			if (index == -2) return;
		}
		BlockPos target;
		Component where;
		boolean down = travel != Travel.UP;
		if (down) {
			int dest = -1;
			if (travel == Travel.DEEPEST) {
				for (int j = floors.length - 1; j > index && dest < 0; j--) if (data(level).get(floors[j]).state() == CLEARED) dest = floors[j];
			} else {
				for (int j = index + 1; j < floors.length && dest < 0; j++) if (data(level).get(floors[j]).state() == CLEARED) dest = floors[j];
			}
			if (dest < 0) {
				player.sendOverlayMessage(Component.translatableWithFallback("antcraft.teleporter.no_deeper", "No deeper guardian is defeated yet"));
				return;
			}
			placeTeleporter(level, layout, seed, dest, bounds, true);
			target = teleporterPos(layout, seed, dest, bounds).above();
			where = guardianName(layout, dest);
		} else if (index == -1) {
			player.sendOverlayMessage(Component.translatableWithFallback("antcraft.teleporter.top", "This is the top. Crouch to go down."));
			return;
		} else if (index == 0 && layout instanceof NetherLayout && level.getChunkSource().getGenerator() instanceof NetherStripChunkGenerator gen) {
			// the tall Nether's first boss room: up to the Key Shrine
			target = KeyShrine.chestPos(bounds, gen.layout()).south(2).below();
			where = Component.translatableWithFallback("antcraft.teleporter.key_shrine", "the Key Shrine");
		} else if (index == 0 && surfacePad != null) {
			target = surfacePad.above();
			where = Component.translatableWithFallback("antcraft.teleporter.surface", "the surface");
		} else if (index == 0) {
			BlockPos spawn = level.getRespawnData().pos();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
			target = new BlockPos(spawn.getX(), Math.max(y, spawn.getY()), spawn.getZ());
			where = Component.translatableWithFallback("antcraft.teleporter.surface", "the surface");
		} else {
			int above = floors[index - 1];
			placeTeleporter(level, layout, seed, above, bounds, data(level).get(above).state() == CLEARED);
			target = teleporterPos(layout, seed, above, bounds).above();
			where = guardianName(layout, above);
		}
		LAST_TRAVEL.put(player.getUUID(), level.getServer().getTickCount());
		level.playSound(null, from, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
		player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
		level.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
		player.sendOverlayMessage(down
			? Component.translatableWithFallback("antcraft.teleporter.arrived_down", "Teleported down to %s", where)
			: Component.translatableWithFallback("antcraft.teleporter.arrived", "Teleported up to %s", where));
	}

	/** Pedestal + button (also added to boss rooms generated before the button existed). */
	private static void placeButton(ServerLevel level, ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		BlockPos button = buttonPos(layout, seed, floor, bounds);
		BlockPos pedestal = button.below();
		if (!level.isLoaded(button) || level.getBlockState(button).is(BlockTags.BUTTONS)) return;
		if (!level.getBlockState(pedestal).canBeReplaced() && !level.getBlockState(pedestal).is(Blocks.CHISELED_POLISHED_BLACKSTONE)) return;
		if (!level.getBlockState(button).canBeReplaced()) return;
		level.setBlockAndUpdate(pedestal, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
		level.setBlockAndUpdate(button, Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
	}

	/** A player pressed a button: if it is an idle boss room's start button, the fight begins. */
	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (!(world instanceof ServerLevel level) || player.isSpectator()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		if (!level.getBlockState(pos).is(BlockTags.BUTTONS)) return InteractionResult.PASS;
		StripBounds bounds = Strip.bounds(level);
		ArenaLayout layout = arenaLayout(level);
		if (bounds == null || layout == null) return InteractionResult.PASS;
		long seed = level.getSeed();
		for (int floor : layout.guardianFloors()) {
			if (!pos.equals(buttonPos(layout, seed, floor, bounds)) || data(level).get(floor).state() != IDLE) continue;
			BlockPos hole = holePos(layout, seed, floor, bounds);
			if (!level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL) || !AntCraftConfig.get().guardians) break;
			AABB room = roomBox(layout, seed, floor, bounds);
			List<ServerPlayer> inside = playersIn(level, room, false);
			if (!inside.isEmpty()) startFight(level, layout, bounds, seed, floor, hole, room, inside);
			break;
		}
		return InteractionResult.PASS; // the button still clicks
	}

	private static void startFight(ServerLevel level, ArenaLayout layout, StripBounds bounds, long seed, int floor, BlockPos hole, AABB room, List<ServerPlayer> inside) {
		// a guardian left over from an earlier attempt (its chunk was unloaded during the reset)
		for (Entity old : level.getEntitiesOfClass(Mob.class, room.inflate(2), mob -> mob.entityTags().contains(GUARDIAN_TAG))) old.discard();
		Mob guardian = spawnGuardian(level, layout, floor, hole);
		if (guardian == null) return;
		closeGates(level, layout, seed, floor, bounds);
		data(level).set(floor, FIGHTING, guardian.getUUID());
		level.playSound(null, hole, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 1.2F);
		for (ServerPlayer player : inside) {
			player.sendOverlayMessage(Component.translatableWithFallback("antcraft.arena.start", "%s blocks the way down!", guardianName(layout, floor)));
		}
		AntCraft.LOGGER.info("AntCraft: guardian fight on floor {} started", floor);
	}

	private static void tickFight(ServerLevel level, ArenaLayout layout, StripBounds bounds, long seed, Arena arena) {
		int floor = arena.floor();
		AABB room = roomBox(layout, seed, floor, bounds);
		List<ServerPlayer> inside = playersIn(level, room, false);
		Entity guardian = arena.guardian().map(level::getEntity).orElse(null);
		if (inside.isEmpty()) {
			// wipe: everybody died or left
			if (guardian != null) guardian.discard();
			openGates(level, layout, seed, floor, bounds);
			removeBar(level, floor);
			data(level).set(floor, IDLE, null);
			AntCraft.LOGGER.info("AntCraft: guardian fight on floor {} reset", floor);
			return;
		}
		if (guardian == null || !guardian.isAlive()) {
			if (level.getDifficulty() == Difficulty.PEACEFUL) {
				win(level, layout, bounds, seed, floor); // peaceful removes hostile mobs; don't lock the way down
			} else {
				// gone without dying (e.g. removed by a command): reset, the next tick starts over
				openGates(level, layout, seed, floor, bounds);
				removeBar(level, floor);
				data(level).set(floor, IDLE, null);
			}
			return;
		}
		closeGates(level, layout, seed, floor, bounds);
		ServerBossEvent bar = BARS.computeIfAbsent(new RoomKey(level.dimension(), floor), key -> {
			ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(), guardianName(layout, key.floor()), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
			event.setDarkenScreen(true);
			return event;
		});
		for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
			if (!inside.contains(player)) bar.removePlayer(player);
		}
		for (ServerPlayer player : inside) bar.addPlayer(player);
		updateBar(level, arena);
	}

	private static void updateBar(ServerLevel level, Arena arena) {
		ServerBossEvent bar = BARS.get(new RoomKey(level.dimension(), arena.floor()));
		if (bar == null || arena.guardian().isEmpty()) return;
		if (level.getEntity(arena.guardian().get()) instanceof LivingEntity guardian) {
			bar.setProgress(Math.clamp(guardian.getHealth() / guardian.getMaxHealth(), 0.0F, 1.0F));
		}
	}

	private static void removeBar(ServerLevel level, int floor) {
		ServerBossEvent bar = BARS.remove(new RoomKey(level.dimension(), floor));
		if (bar != null) bar.removeAllPlayers();
	}

	/**
	 * A guardian that splits on death (the Molten Sovereign) hands its tags, name, effects and
	 * persistence to the cubes it splits into ({@code ConversionType.convertCommon}). The children
	 * are ordinary magma cubes: untagged, unnamed, without the guardian's effects, and they may
	 * despawn. Fabric fires this before the child is added to the level.
	 */
	private static void onConversion(net.minecraft.world.entity.Mob from, net.minecraft.world.entity.Mob to, net.minecraft.world.entity.ConversionParams params) {
		if (params.type() != net.minecraft.world.entity.ConversionType.SPLIT_ON_DEATH || !from.entityTags().contains(GUARDIAN_TAG)) return;
		to.removeTag(GUARDIAN_TAG);
		to.setCustomName(null);
		to.setCustomNameVisible(false);
		to.removeAllEffects();
		((dev.antcraft.mixin.MobAccessor) to).antcraft$setPersistenceRequired(false);
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level) || !entity.entityTags().contains(GUARDIAN_TAG)) return;
		StripBounds bounds = Strip.bounds(level);
		ArenaLayout layout = arenaLayout(level);
		if (bounds == null || layout == null) return;
		Data data = data(level);
		for (int floor : layout.guardianFloors()) {
			Arena arena = data.get(floor);
			if (arena.state() == FIGHTING && arena.guardian().isPresent() && arena.guardian().get().equals(entity.getUUID())) {
				if (entity.getType() == EntityTypes.EVOKER) {
					// the Canyon Conjurer's vexes vanish with it
					AABB area = roomBox(layout, level.getSeed(), floor, bounds).inflate(16);
					for (net.minecraft.world.entity.monster.Vex vex : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Vex.class, area,
						v -> v.getOwner() == null || v.getOwner() == entity)) vex.discard();
				}
				win(level, layout, bounds, level.getSeed(), floor);
				if (source.getEntity() instanceof ServerPlayer killer) LayerTracker.award(killer, advancementFor(layout, floor), "defeated");
				return;
			}
		}
	}

	/** The advancement for defeating the guardian of a floor. */
	private static String advancementFor(ArenaLayout layout, int floor) {
		GuardianId g = layout.guardian(floor);
		return g != null ? g.advancementId : "guardian_" + floor;
	}

	private static void win(ServerLevel level, ArenaLayout layout, StripBounds bounds, long seed, int floor) {
		BlockPos hole = holePos(layout, seed, floor, bounds);
		openGates(level, layout, seed, floor, bounds);
		if (level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL)) SealBlock.open(level, hole);
		removeBar(level, floor);
		data(level).set(floor, CLEARED, null);
		placeTeleporter(level, layout, seed, floor, bounds, true);
		level.playSound(null, hole, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
		for (ServerPlayer player : playersIn(level, roomBox(layout, seed, floor, bounds), false)) {
			LayerTracker.award(player, advancementFor(layout, floor), "defeated");
			player.sendOverlayMessage(Component.translatableWithFallback("antcraft.arena.won", "%s is defeated. The way down is open.", guardianName(layout, floor)));
			if (level.dimension() == Level.OVERWORLD) player.sendSystemMessage(Component.translatableWithFallback(
				"antcraft.arena.surface_teleporter", "A teleporter at the surface spawn now leads down to the deepest defeated guardian."));
		}
		AntCraft.LOGGER.info("AntCraft: guardian of floor {} defeated", floor);
	}

	private static void closeGates(ServerLevel level, ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		BlockState gate = ModContent.ARENA_GATE.defaultBlockState();
		for (BlockPos pos : gatePositions(layout, seed, floor, bounds)) {
			if (!level.isLoaded(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.is(ModContent.ARENA_GATE) || state.is(Blocks.BEDROCK)) continue;
			// never shut a gate on somebody standing in the doorway
			if (!level.getEntitiesOfClass(Player.class, new AABB(pos)).isEmpty()) continue;
			level.setBlockAndUpdate(pos, gate);
		}
	}

	private static void openGates(ServerLevel level, ArenaLayout layout, long seed, int floor, StripBounds bounds) {
		for (BlockPos pos : gatePositions(layout, seed, floor, bounds)) {
			if (level.isLoaded(pos) && level.getBlockState(pos).is(ModContent.ARENA_GATE)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		}
	}

	/** True if the hole of this floor is still guarded (for the action-bar hint). */
	public static boolean isGuarded(BlockState holeState) {
		return holeState.is(ModContent.GUARDIAN_SEAL);
	}
}
