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
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The boss rooms on floors 0, 2 and 6 ({@link Strip#GUARDIAN_FLOORS}). The floor hall around the
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
	public static int wallZ(long seed, int floor, StripBounds bounds, int side) {
		return Strip.holeZ(seed, floor, bounds) + side * WALL_OFFSET;
	}

	public static boolean isDoorway(StripBounds bounds, int floor, int x, int y) {
		int floorTop = Strip.floorTop(floor);
		return Math.abs(x - bounds.centerX()) <= DOOR_HALF_WIDTH && y > floorTop && y <= floorTop + DOOR_HEIGHT;
	}

	/** The room's inside, from the floor up to the hall ceiling. */
	public static AABB roomBox(long seed, int floor, StripBounds bounds) {
		int hz = Strip.holeZ(seed, floor, bounds);
		int floorTop = Strip.floorTop(floor);
		int minZ = Math.max(bounds.minZ(), hz - WALL_OFFSET + 1), maxZ = Math.min(bounds.maxZ(), hz + WALL_OFFSET - 1);
		return new AABB(bounds.minX(), floorTop + 1, minZ, bounds.maxX() + 1, floorTop + 1 + Strip.HALL_HEIGHT + WALL_EXTRA_HEIGHT, maxZ + 1);
	}

	private static BlockPos holePos(long seed, int floor, StripBounds bounds) {
		return new BlockPos(Strip.holeX(seed, floor, bounds), Strip.floorTop(floor), Strip.holeZ(seed, floor, bounds));
	}

	/**
	 * The start button: on a pedestal 3 blocks from the hole along z (towards the strip's middle
	 * if the hole is near an end), clear of the hole's lanterns.
	 */
	static BlockPos buttonPos(long seed, int floor, StripBounds bounds) {
		int hx = Strip.holeX(seed, floor, bounds), hz = Strip.holeZ(seed, floor, bounds);
		int z = hz + 3 <= bounds.maxZ() - 1 ? hz + 3 : hz - 3;
		return new BlockPos(hx, Strip.floorTop(floor) + 2, z);
	}

	private static List<BlockPos> gatePositions(long seed, int floor, StripBounds bounds) {
		List<BlockPos> list = new ArrayList<>();
		int floorTop = Strip.floorTop(floor);
		for (int side = -1; side <= 1; side += 2) {
			int z = wallZ(seed, floor, bounds, side);
			if (z < bounds.minZ() || z > bounds.maxZ()) continue;
			for (int x = bounds.centerX() - DOOR_HALF_WIDTH; x <= bounds.centerX() + DOOR_HALF_WIDTH; x++) {
				for (int y = floorTop + 1; y <= floorTop + DOOR_HEIGHT; y++) list.add(new BlockPos(x, y, z));
			}
		}
		return list;
	}

	// ------------------------------------------------------------------ guardians

	private record Guardian(EntityType<? extends Mob> type, String name, double health, double scale) {}

	private static @Nullable Guardian guardianFor(int floor) {
		return switch (floor) {
			case 0 -> new Guardian(EntityTypes.VINDICATOR, "Tunnel Captain", 60, 1.0);
			case 2 -> new Guardian(EntityTypes.RAVAGER, "Deep Beast", 180, 1.0);
			case 6 -> new Guardian(EntityTypes.HUSK, "Desert Colossus", 200, 2.5);
			default -> null;
		};
	}

	private static Component guardianName(int floor) {
		Guardian g = guardianFor(floor);
		return Component.translatableWithFallback("antcraft.guardian." + floor, g == null ? "Guardian" : g.name());
	}

	private static @Nullable Mob spawnGuardian(ServerLevel level, int floor, BlockPos hole) {
		Guardian g = guardianFor(floor);
		if (g == null) return null;
		Mob mob = g.type().create(level, EntitySpawnReason.EVENT);
		if (mob == null) return null;
		mob.snapTo(hole.getX() + 0.5, hole.getY() + 1, hole.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
		setBase(mob, Attributes.MAX_HEALTH, g.health());
		if (g.scale() != 1.0) setBase(mob, Attributes.SCALE, g.scale());
		setBase(mob, Attributes.FOLLOW_RANGE, 40.0);
		setBase(mob, Attributes.KNOCKBACK_RESISTANCE, 0.6);
		mob.setHealth(mob.getMaxHealth());
		mob.setCustomName(guardianName(floor));
		mob.setCustomNameVisible(true);
		mob.setPersistenceRequired();
		mob.addTag(GUARDIAN_TAG);
		switch (floor) {
			case 0 -> {
				mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
				mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
			}
			case 6 -> mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 1));
			default -> {}
		}
		level.addFreshEntity(mob);
		return mob;
	}

	private static void setBase(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
		AttributeInstance instance = entity.getAttribute(attribute);
		if (instance != null) instance.setBaseValue(value);
	}

	// ------------------------------------------------------------------ state

	private record Arena(int floor, int state, Optional<UUID> guardian) {
		static final Codec<Arena> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.INT.fieldOf("floor").forGetter(Arena::floor),
					Codec.INT.fieldOf("state").forGetter(Arena::state),
					UUIDUtil.CODEC.optionalFieldOf("guardian").forGetter(Arena::guardian)
				)
				.apply(i, Arena::new)
		);
	}

	/** Per-world arena states, in the overworld's data storage ({@code data/antcraft_arenas.dat}). */
	public static final class Data extends SavedData {
		static final Codec<Data> CODEC = Arena.CODEC.listOf().xmap(Data::new, data -> List.copyOf(data.arenas.values())).fieldOf("arenas").codec();
		static final SavedDataType<Data> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "arenas"), Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE
		);

		private final Map<Integer, Arena> arenas = new HashMap<>();

		Data() {}

		Data(List<Arena> list) {
			for (Arena arena : list) this.arenas.put(arena.floor(), arena);
		}

		Arena get(int floor) {
			return this.arenas.getOrDefault(floor, new Arena(floor, IDLE, Optional.empty()));
		}

		void set(int floor, int state, @Nullable UUID guardian) {
			this.arenas.put(floor, new Arena(floor, state, Optional.ofNullable(guardian)));
			this.setDirty();
		}
	}

	private static Data data(ServerLevel overworld) {
		return overworld.getDataStorage().computeIfAbsent(Data.TYPE);
	}

	/** {@link #IDLE}, {@link #FIGHTING} or {@link #CLEARED} (for {@link SelfCheck}). */
	static int state(ServerLevel overworld, int floor) {
		return data(overworld).get(floor).state();
	}

	/** Boss bars of running fights (not saved; recreated after a restart). */
	private static final Map<Integer, ServerBossEvent> BARS = new HashMap<>();

	// ------------------------------------------------------------------ ticking

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaManager::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register(ArenaManager::onDeath);
		UseBlockCallback.EVENT.register(ArenaManager::onUseBlock);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BARS.values().forEach(ServerBossEvent::removeAllPlayers);
			BARS.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		StripBounds bounds = Strip.bounds(overworld);
		if (bounds == null) return;
		boolean full = server.getTickCount() % 10 == 0;
		long seed = overworld.getSeed();
		for (int floor : Strip.GUARDIAN_FLOORS) {
			Arena arena = data(overworld).get(floor);
			if (arena.state() == FIGHTING) {
				if (full) tickFight(overworld, bounds, seed, arena);
				else updateBar(overworld, arena);
			} else if (arena.state() == IDLE && full) {
				tickIdle(overworld, bounds, seed, floor);
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

	private static void tickIdle(ServerLevel level, StripBounds bounds, long seed, int floor) {
		AABB room = roomBox(seed, floor, bounds);
		if (playersIn(level, room.inflate(4, 2, 12), false).isEmpty()) return;
		// gates left closed (a fight that was reset while the room was unloaded): open them
		openGates(level, seed, floor, bounds);
		BlockPos hole = holePos(seed, floor, bounds);
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
		placeButton(level, seed, floor, bounds);
	}

	/** Pedestal + button (also added to boss rooms generated before the button existed). */
	private static void placeButton(ServerLevel level, long seed, int floor, StripBounds bounds) {
		BlockPos button = buttonPos(seed, floor, bounds);
		BlockPos pedestal = button.below();
		if (!level.isLoaded(button) || level.getBlockState(button).is(BlockTags.BUTTONS)) return;
		if (!level.getBlockState(pedestal).canBeReplaced() && !level.getBlockState(pedestal).is(Blocks.CHISELED_POLISHED_BLACKSTONE)) return;
		if (!level.getBlockState(button).canBeReplaced()) return;
		level.setBlockAndUpdate(pedestal, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
		level.setBlockAndUpdate(button, Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
	}

	/** A player pressed a button: if it is an idle boss room's start button, the fight begins. */
	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (!(world instanceof ServerLevel level) || level != level.getServer().overworld() || player.isSpectator()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		if (!level.getBlockState(pos).is(BlockTags.BUTTONS)) return InteractionResult.PASS;
		StripBounds bounds = Strip.bounds(level);
		if (bounds == null) return InteractionResult.PASS;
		long seed = level.getSeed();
		for (int floor : Strip.GUARDIAN_FLOORS) {
			if (!pos.equals(buttonPos(seed, floor, bounds)) || data(level).get(floor).state() != IDLE) continue;
			BlockPos hole = holePos(seed, floor, bounds);
			if (!level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL) || !AntCraftConfig.get().guardians) break;
			AABB room = roomBox(seed, floor, bounds);
			List<ServerPlayer> inside = playersIn(level, room, false);
			if (!inside.isEmpty()) startFight(level, bounds, seed, floor, hole, room, inside);
			break;
		}
		return InteractionResult.PASS; // the button still clicks
	}

	private static void startFight(ServerLevel level, StripBounds bounds, long seed, int floor, BlockPos hole, AABB room, List<ServerPlayer> inside) {
		// a guardian left over from an earlier attempt (its chunk was unloaded during the reset)
		for (Entity old : level.getEntitiesOfClass(Mob.class, room.inflate(2), mob -> mob.entityTags().contains(GUARDIAN_TAG))) old.discard();
		Mob guardian = spawnGuardian(level, floor, hole);
		if (guardian == null) return;
		closeGates(level, seed, floor, bounds);
		data(level).set(floor, FIGHTING, guardian.getUUID());
		level.playSound(null, hole, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 1.2F);
		for (ServerPlayer player : inside) {
			player.sendOverlayMessage(Component.translatableWithFallback("antcraft.arena.start", "%s blocks the way down!", guardianName(floor)));
		}
		AntCraft.LOGGER.info("AntCraft: guardian fight on floor {} started", floor);
	}

	private static void tickFight(ServerLevel level, StripBounds bounds, long seed, Arena arena) {
		int floor = arena.floor();
		AABB room = roomBox(seed, floor, bounds);
		List<ServerPlayer> inside = playersIn(level, room, false);
		Entity guardian = arena.guardian().map(level::getEntity).orElse(null);
		if (inside.isEmpty()) {
			// wipe: everybody died or left
			if (guardian != null) guardian.discard();
			openGates(level, seed, floor, bounds);
			removeBar(floor);
			data(level).set(floor, IDLE, null);
			AntCraft.LOGGER.info("AntCraft: guardian fight on floor {} reset", floor);
			return;
		}
		if (guardian == null || !guardian.isAlive()) {
			if (level.getDifficulty() == Difficulty.PEACEFUL) {
				win(level, bounds, seed, floor); // peaceful removes hostile mobs; don't lock the way down
			} else {
				// gone without dying (e.g. removed by a command): reset, the next tick starts over
				openGates(level, seed, floor, bounds);
				removeBar(floor);
				data(level).set(floor, IDLE, null);
			}
			return;
		}
		closeGates(level, seed, floor, bounds);
		ServerBossEvent bar = BARS.computeIfAbsent(floor, f -> {
			ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(), guardianName(f), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
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
		ServerBossEvent bar = BARS.get(arena.floor());
		if (bar == null || arena.guardian().isEmpty()) return;
		if (level.getEntity(arena.guardian().get()) instanceof LivingEntity guardian) {
			bar.setProgress(Math.clamp(guardian.getHealth() / guardian.getMaxHealth(), 0.0F, 1.0F));
		}
	}

	private static void removeBar(int floor) {
		ServerBossEvent bar = BARS.remove(floor);
		if (bar != null) bar.removeAllPlayers();
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level) || !entity.entityTags().contains(GUARDIAN_TAG)) return;
		if (level != level.getServer().overworld()) return;
		StripBounds bounds = Strip.bounds(level);
		if (bounds == null) return;
		Data data = data(level);
		for (int floor : Strip.GUARDIAN_FLOORS) {
			Arena arena = data.get(floor);
			if (arena.state() == FIGHTING && arena.guardian().isPresent() && arena.guardian().get().equals(entity.getUUID())) {
				win(level, bounds, level.getSeed(), floor);
				if (source.getEntity() instanceof ServerPlayer killer) LayerTracker.award(killer, "guardian_" + floor, "defeated");
				return;
			}
		}
	}

	private static void win(ServerLevel level, StripBounds bounds, long seed, int floor) {
		BlockPos hole = holePos(seed, floor, bounds);
		openGates(level, seed, floor, bounds);
		if (level.getBlockState(hole).is(ModContent.GUARDIAN_SEAL)) SealBlock.open(level, hole);
		removeBar(floor);
		data(level).set(floor, CLEARED, null);
		level.playSound(null, hole, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
		for (ServerPlayer player : playersIn(level, roomBox(seed, floor, bounds), false)) {
			LayerTracker.award(player, "guardian_" + floor, "defeated");
			player.sendOverlayMessage(Component.translatableWithFallback("antcraft.arena.won", "%s is defeated. The way down is open.", guardianName(floor)));
		}
		AntCraft.LOGGER.info("AntCraft: guardian of floor {} defeated", floor);
	}

	private static void closeGates(ServerLevel level, long seed, int floor, StripBounds bounds) {
		BlockState gate = ModContent.ARENA_GATE.defaultBlockState();
		for (BlockPos pos : gatePositions(seed, floor, bounds)) {
			if (!level.isLoaded(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.is(ModContent.ARENA_GATE) || state.is(Blocks.BEDROCK)) continue;
			// never shut a gate on somebody standing in the doorway
			if (!level.getEntitiesOfClass(Player.class, new AABB(pos)).isEmpty()) continue;
			level.setBlockAndUpdate(pos, gate);
		}
	}

	private static void openGates(ServerLevel level, long seed, int floor, StripBounds bounds) {
		for (BlockPos pos : gatePositions(seed, floor, bounds)) {
			if (level.isLoaded(pos) && level.getBlockState(pos).is(ModContent.ARENA_GATE)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		}
	}

	/** True if the hole of this floor is still guarded (for the action-bar hint). */
	public static boolean isGuarded(BlockState holeState) {
		return holeState.is(ModContent.GUARDIAN_SEAL);
	}
}
