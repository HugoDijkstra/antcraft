package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The tall End's runtime (End layout 2):
 * <ul>
 *   <li><b>void kill</b> (spec 5.1): every 10 ticks, living entities below y -64 with z >= -256
 *       (the fight zone and beyond) take out-of-world damage as in a 0..255 End (4 per tick);
 *       other entities there are removed;</li>
 *   <li>the <b>Void Stalker</b> (E5): a survival player on its arena wakes it; when it dies the
 *       E5 Rift Gate opens;</li>
 *   <li>the <b>Phantom Matriarch</b> (U4): a survival player in the closed arena wakes it; when it
 *       dies a chest with the Void Key appears;</li>
 *   <li>tier titles and the End advancements (Ascent, Underside, Crown).</li>
 * </ul>
 * Arena states are saved with the world ({@code data/antcraft_end_arenas.dat}).
 */
public final class EndArenas {
	private EndArenas() {}

	static final int IDLE = 0, FIGHTING = 1, CLEARED = 2;
	public static final String GUARDIAN_TAG = "antcraft.end_guardian";
	public static final String STALKER = "void_stalker", MATRIARCH = "phantom_matriarch";

	private record Arena(String id, int state, Optional<UUID> guardian) {
		static final Codec<Arena> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					Codec.STRING.fieldOf("id").forGetter(Arena::id),
					Codec.INT.fieldOf("state").forGetter(Arena::state),
					UUIDUtil.CODEC.optionalFieldOf("guardian").forGetter(Arena::guardian)
				)
				.apply(i, Arena::new)
		);
	}

	public static final class Data extends SavedData {
		static final Codec<Data> CODEC = Arena.CODEC.listOf().xmap(Data::new, data -> List.copyOf(data.arenas.values())).fieldOf("arenas").codec();
		static final SavedDataType<Data> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "end_arenas"), Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE
		);

		private final Map<String, Arena> arenas = new LinkedHashMap<>();

		Data() {}

		Data(List<Arena> list) {
			for (Arena arena : list) this.arenas.put(arena.id(), arena);
		}

		Arena get(String id) {
			return this.arenas.getOrDefault(id, new Arena(id, IDLE, Optional.empty()));
		}

		void set(String id, int state, @Nullable UUID guardian) {
			this.arenas.put(id, new Arena(id, state, Optional.ofNullable(guardian)));
			this.setDirty();
		}
	}

	private static Data data(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(Data.TYPE);
	}

	/** {@link #IDLE}, {@link #FIGHTING} or {@link #CLEARED}. */
	public static int state(ServerLevel level, String id) {
		return data(level).get(id).state();
	}

	private static final Map<String, ServerBossEvent> BARS = new HashMap<>();
	private static final Map<UUID, String> LAST_TIER = new HashMap<>();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(EndArenas::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register(EndArenas::onDeath);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BARS.values().forEach(ServerBossEvent::removeAllPlayers);
			BARS.clear();
			LAST_TIER.clear();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_TIER.remove(handler.player.getUUID()));
	}

	// ------------------------------------------------------------------ arenas

	/** One End arena: its tier, its box (the players who count), where its guardian appears. */
	record Spec(String id, EndLayout.Tier tier, AABB box, BlockPos spawn) {}

	static List<Spec> arenas(StripBounds bounds, EndLayout layout) {
		List<Spec> list = new ArrayList<>();
		for (EndLayout.Tier t : layout.tiers) {
			if (t.stalkerArena()) {
				int[] zr = layout.stalkerArenaZ(t);
				AABB box = new AABB(bounds.minX(), t.y() + 1, zr[0] + 1, bounds.maxX() + 1, t.y() + 10, zr[1]);
				list.add(new Spec(STALKER, t, box, new BlockPos(bounds.centerX(), t.y() + 1, (zr[0] + zr[1]) / 2)));
			}
			if (t.shape() == EndLayout.Shape.ARENA) {
				int[] zr = layout.matriarchArenaZ(t);
				AABB box = new AABB(bounds.minX() + 1, t.y() + 1, zr[0] + 1, bounds.maxX(), t.y() + EndLayout.ARENA_HEIGHT, zr[1]);
				list.add(new Spec(MATRIARCH, t, box, new BlockPos(bounds.centerX(), t.y() + 8, (zr[0] + zr[1]) / 2)));
			}
		}
		return list;
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		ServerLevel end = server.getLevel(Level.END);
		EndStripChunkGenerator gen = EndStripChunkGenerator.tallEnd(end);
		if (gen == null) return;
		voidKill(end);
		for (Spec spec : arenas(gen.bounds(), gen.endLayout())) tickArena(end, gen, spec);
		boolean titles = AntCraftConfig.get().layerTitles;
		for (ServerPlayer player : end.players()) track(player, gen.endLayout(), titles);
	}

	/** Spec 5.1: the vanilla void, 64 blocks under the band, for the fight zone and everything on its side. */
	private static void voidKill(ServerLevel level) {
		List<Entity> doomed = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity.getY() < EndLayout.VOID_KILL_Y && entity.getZ() >= EndLayout.FIGHT_MIN_Z && !entity.isSpectator()) doomed.add(entity);
		}
		for (Entity entity : doomed) {
			if (entity instanceof LivingEntity living) {
				living.hurtServer(level, level.damageSources().fellOutOfWorld(), 40.0F); // 10 ticks of vanilla's 4 per tick
			} else {
				entity.discard();
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

	private static void tickArena(ServerLevel level, EndStripChunkGenerator gen, Spec spec) {
		Data data = data(level);
		Arena arena = data.get(spec.id());
		if (!level.isLoaded(spec.spawn())) return;
		switch (arena.state()) {
			case IDLE -> {
				List<ServerPlayer> inside = playersIn(level, spec.box(), true);
				if (inside.isEmpty()) return;
				if (level.getDifficulty() == Difficulty.PEACEFUL) {
					win(level, gen, spec, null);
					return;
				}
				Mob guardian = spawnGuardian(level, spec);
				if (guardian == null) return;
				data.set(spec.id(), FIGHTING, guardian.getUUID());
				level.playSound(null, spec.spawn(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.8F);
				for (ServerPlayer player : inside) {
					player.sendOverlayMessage(Component.translatableWithFallback("antcraft.end_arena.start", "%s wakes!", guardianName(spec.id())));
				}
				AntCraft.LOGGER.info("AntCraft: End arena {} started", spec.id());
			}
			case FIGHTING -> {
				Entity guardian = arena.guardian().map(level::getEntity).orElse(null);
				List<ServerPlayer> near = playersIn(level, spec.box().inflate(16, 16, 16), false);
				if (near.isEmpty()) {
					if (guardian != null) guardian.discard();
					reset(level, spec);
					return;
				}
				if (guardian == null || !guardian.isAlive()) {
					if (level.getDifficulty() == Difficulty.PEACEFUL) win(level, gen, spec, null);
					else reset(level, spec);
					return;
				}
				// a teleporting Void Stalker stays near its arena; both keep after the players
				if (!spec.box().inflate(24, 24, 24).contains(guardian.position())) {
					guardian.teleportTo(spec.spawn().getX() + 0.5, spec.spawn().getY(), spec.spawn().getZ() + 0.5);
				}
				if (guardian instanceof Mob mob && (mob.getTarget() == null || !mob.getTarget().isAlive())) {
					ServerPlayer target = null;
					double best = Double.MAX_VALUE;
					for (ServerPlayer p : near) {
						if (p.isCreative()) continue;
						double d = p.distanceToSqr(mob);
						if (d < best) {
							best = d;
							target = p;
						}
					}
					if (target != null) mob.setTarget(target);
				}
				ServerBossEvent bar = BARS.computeIfAbsent(spec.id(), id -> {
					ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(), guardianName(id), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
					event.setDarkenScreen(true);
					return event;
				});
				for (ServerPlayer player : List.copyOf(bar.getPlayers())) if (!near.contains(player)) bar.removePlayer(player);
				for (ServerPlayer player : near) bar.addPlayer(player);
				if (guardian instanceof LivingEntity living) bar.setProgress(Math.clamp(living.getHealth() / living.getMaxHealth(), 0.0F, 1.0F));
			}
			default -> {
				// cleared: the reward stays (re-placed if a player removed the E5 gateway)
				if (spec.id().equals(STALKER) && !playersIn(level, spec.box().inflate(8, 8, 8), false).isEmpty()) openStalkerGate(level, gen);
			}
		}
	}

	private static void reset(ServerLevel level, Spec spec) {
		ServerBossEvent bar = BARS.remove(spec.id());
		if (bar != null) bar.removeAllPlayers();
		data(level).set(spec.id(), IDLE, null);
		AntCraft.LOGGER.info("AntCraft: End arena {} reset", spec.id());
	}

	static Component guardianName(String id) {
		return id.equals(STALKER)
			? Component.translatableWithFallback("antcraft.guardian.void_stalker", "Void Stalker")
			: Component.translatableWithFallback("antcraft.guardian.phantom_matriarch", "Phantom Matriarch");
	}

	private static @Nullable Mob spawnGuardian(ServerLevel level, Spec spec) {
		boolean stalker = spec.id().equals(STALKER);
		EntityType<? extends Mob> type = stalker ? EntityTypes.ENDERMAN : EntityTypes.PHANTOM;
		Mob mob = type.create(level, EntitySpawnReason.EVENT);
		if (mob == null) return null;
		BlockPos at = spec.spawn();
		mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
		setBase(mob, Attributes.MAX_HEALTH, stalker ? 320.0 : 260.0);
		setBase(mob, Attributes.SCALE, stalker ? 2.0 : 3.0);
		setBase(mob, Attributes.FOLLOW_RANGE, 40.0);
		setBase(mob, Attributes.KNOCKBACK_RESISTANCE, 0.6);
		mob.setHealth(mob.getMaxHealth());
		if (stalker) {
			mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 0));
		} else {
			mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0));
		}
		mob.setCustomName(guardianName(spec.id()));
		mob.setCustomNameVisible(true);
		mob.setPersistenceRequired();
		mob.addTag(GUARDIAN_TAG);
		mob.addTag(GUARDIAN_TAG + "." + spec.id());
		level.addFreshEntity(mob);
		return mob;
	}

	private static void setBase(LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
		AttributeInstance instance = entity.getAttribute(attribute);
		if (instance != null) instance.setBaseValue(value);
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level) || !entity.entityTags().contains(GUARDIAN_TAG)) return;
		EndStripChunkGenerator gen = EndStripChunkGenerator.tallEnd(level);
		if (gen == null) return;
		for (Spec spec : arenas(gen.bounds(), gen.endLayout())) {
			Arena arena = data(level).get(spec.id());
			if (arena.state() == FIGHTING && arena.guardian().isPresent() && arena.guardian().get().equals(entity.getUUID())) {
				win(level, gen, spec, source.getEntity() instanceof ServerPlayer killer ? killer : null);
				return;
			}
		}
	}

	private static void win(ServerLevel level, EndStripChunkGenerator gen, Spec spec, @Nullable ServerPlayer killer) {
		ServerBossEvent bar = BARS.remove(spec.id());
		if (bar != null) bar.removeAllPlayers();
		data(level).set(spec.id(), CLEARED, null);
		Component message;
		String advancement;
		if (spec.id().equals(STALKER)) {
			openStalkerGate(level, gen);
			advancement = "guardian_void_stalker";
			message = Component.translatableWithFallback("antcraft.end_arena.stalker_won", "The Void Stalker is defeated. The Rift Gate is open.");
		} else {
			placeKeyChest(level, gen, spec.tier());
			advancement = "guardian_phantom_matriarch";
			message = Component.translatableWithFallback("antcraft.end_arena.matriarch_won", "The Phantom Matriarch is defeated. The Void Key is yours.");
		}
		level.playSound(null, spec.spawn(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
		if (killer != null) LayerTracker.award(killer, advancement, "defeated");
		for (ServerPlayer player : playersIn(level, spec.box().inflate(16, 16, 16), false)) {
			LayerTracker.award(player, advancement, "defeated");
			player.sendOverlayMessage(message);
		}
		AntCraft.LOGGER.info("AntCraft: End arena {} cleared", spec.id());
	}

	/** The E5 Rift Gate (an arena gate block until the Void Stalker dies): a gateway with its exact exit. */
	static void openStalkerGate(ServerLevel level, EndStripChunkGenerator gen) {
		EndLayout layout = gen.endLayout();
		for (EndLayout.Tier t : layout.tiers) {
			if (!t.stalkerArena()) continue;
			EndLayout.Gate gate = layout.gate(gen.bounds(), t.id() + "_rift");
			if (gate == null || !level.isLoaded(gate.pos())) return;
			if (level.getBlockState(gate.pos()).is(Blocks.END_GATEWAY)) return;
			placeGateway(level, gate.pos(), gate.target());
			level.playSound(null, gate.pos(), SoundEvents.END_GATEWAY_SPAWN, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
	}

	/** A gateway on bedrock with a bedrock cap, sending you exactly to {@code target}. */
	static void placeGateway(ServerLevel level, BlockPos pos, BlockPos target) {
		level.setBlock(pos.below(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pos.above(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pos, Blocks.END_GATEWAY.defaultBlockState(), Block.UPDATE_ALL);
		if (level.getBlockEntity(pos) instanceof TheEndGatewayBlockEntity gateway) gateway.setExitPosition(target, true);
	}

	private static void placeKeyChest(ServerLevel level, EndStripChunkGenerator gen, EndLayout.Tier tier) {
		BlockPos chest = gen.endLayout().voidKeyChest(gen.bounds(), tier);
		if (level.getBlockState(chest).is(Blocks.CHEST)) return;
		level.setBlock(chest.below(), Blocks.CRYING_OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
		if (level.getBlockEntity(chest) instanceof ChestBlockEntity be) {
			be.setItem(13, new ItemStack(ModContent.VOID_KEY));
			be.setItem(11, new ItemStack(net.minecraft.world.item.Items.PHANTOM_MEMBRANE, 8));
			be.setItem(15, new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL, 8));
		}
	}

	// ------------------------------------------------------------------ titles and advancements

	/** Tier titles, and the advancements for reaching the Ascent, the Underside and the Crown. */
	private static void track(ServerPlayer player, EndLayout layout, boolean titles) {
		EndLayout.Tier tier = EndLayout.inZone(player.getBlockZ()) ? layout.tierAt(player.getBlockY()) : null;
		String id = tier == null ? "" : tier.id();
		String last = LAST_TIER.put(player.getUUID(), id);
		if (tier == null || id.equals(last)) return;
		LayerTracker.award(player, tier.ascent() ? "end_ascent" : "end_underside", "reached");
		if (tier.shape() == EndLayout.Shape.CROWN) LayerTracker.award(player, "crown_of_the_end", "reached");
		if (titles) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(tier.ascent()
				? Component.translatableWithFallback("antcraft.end_tier.ascent_subtitle", "The Ascent — tier %s", tier.step())
				: Component.translatableWithFallback("antcraft.end_tier.underside_subtitle", "The Underside — tier %s", tier.step())));
			player.connection.send(new ClientboundSetTitleTextPacket(tier.displayName()));
		}
	}
}
