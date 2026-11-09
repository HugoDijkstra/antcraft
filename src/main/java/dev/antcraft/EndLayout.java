package dev.antcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.Nullable;

/**
 * The End strip's vertical layout (spec v2 section 5). Layout 1 is the End as it always was (a
 * 0..255 dimension). Layout 2 is the tall End: the vanilla End stays at y 0..255 (D_E = 0) in a
 * -2032..2031 dimension, with the Ascent above it and the Underside below it, both inside the
 * tier zone z -1400..-300. The fight zone z -256..149 keeps the vanilla 0..255 band only.
 *
 * <p>Every tier runs along the whole tier zone. Its start platform (where you arrive, with a
 * Return Gate) and its exit platform (with the Rift Gate to the next tier) sit at opposite ends,
 * and the ends alternate from tier to tier. The base platform at z -1150 holds the Ascent Gate
 * (to E1) and the Descent Gate (to U1).
 */
public final class EndLayout {
	public static final int LATEST = 2;

	/** The protected fight zone (the whole strip width): no generated blocks above {@link #BASE_MAX_Y} or below {@link #BASE_MIN_Y}. */
	public static final int FIGHT_MIN_Z = -256, FIGHT_MAX_Z = 149;
	/** The vanilla End band. */
	public static final int BASE_MIN_Y = 0, BASE_MAX_Y = 255;
	/** The tier zone: the Ascent and the Underside lie inside it. */
	public static final int ZONE_MIN_Z = -1400, ZONE_MAX_Z = -300;
	/** Living entities below this y with z >= {@link #FIGHT_MIN_Z} die as in the vanilla void. */
	public static final int VOID_KILL_Y = -64;
	/** The two ends of every tier (platform centres). */
	public static final int END_A = -1360, END_B = -340;
	/** The base platform with the Ascent and Descent Gates (floor y, centre z). */
	public static final int BASE_PLATFORM_Y = 72, BASE_PLATFORM_Z = -1150;
	/** Tier islands reach this far above and below the tier y. */
	public static final int ISLAND_RANGE = 32;

	public static final EndLayout LAYOUT_1 = new EndLayout(1, false, 0, 255);
	public static final EndLayout LAYOUT_2 = new EndLayout(2, true, -2032, 2031);

	public final int id;
	public final boolean tall;
	public final int worldMinY, worldMaxY;
	/** Ascent E1..E6 and the Crown (index 0..6), then Underside U1..U4 (index 7..10). Empty in layout 1. */
	public final List<Tier> tiers;

	/** What a tier's terrain looks like. */
	public enum Shape {
		/** Simplex islands of end stone (most tiers). */
		ISLANDS,
		/** Small scattered islets (E3, U3). */
		ISLETS,
		/** One purpur crypt along the whole tier (U2). */
		CRYPT,
		/** The Crown's citadel on its foundation island. */
		CROWN,
		/** A closed arena around the start platform (U4). */
		ARENA
	}

	/**
	 * One tier. {@code chain}: +1 for the Ascent (E1..E6, Crown = step 7), -1 for the Underside
	 * (U1..U4). {@code threshold}: noise level above which a column is island (lower = more land);
	 * {@code frequency}: island noise frequency.
	 */
	public record Tier(
		String id,
		int chain,
		int step,
		int y,
		ResourceKey<Biome> biome,
		String englishName,
		Shape shape,
		double threshold,
		double frequency,
		/** 0 = none, else the city kind: 1 small city (E1), 2 city with a ship (E2), 6 the Grand Spire (E6). */
		int city,
		boolean chorus,
		boolean endRods,
		boolean stalactites,
		boolean darkTop,
		boolean shulkerVault,
		boolean stalkerArena
	) {
		public boolean ascent() {
			return this.chain > 0;
		}

		/** Where you arrive: END_A for odd steps, END_B for even steps. */
		public int startZ() {
			return this.step % 2 == 1 ? END_A : END_B;
		}

		/** The far end (the exit platform's centre); the Crown and U4 have none. */
		public int exitZ() {
			return this.startZ() == END_A ? END_B : END_A;
		}

		public boolean hasExit() {
			return this.shape != Shape.CROWN && this.shape != Shape.ARENA;
		}

		/** +1 if the tier runs towards +z from its start, else -1. */
		public int dirIn() {
			return this.startZ() == END_A ? 1 : -1;
		}

		public String langKey() {
			return "antcraft.end_tier." + this.id;
		}

		public Component displayName() {
			return Component.translatableWithFallback(this.langKey(), this.englishName);
		}
	}

	private EndLayout(int id, boolean tall, int worldMinY, int worldMaxY) {
		this.id = id;
		this.tall = tall;
		this.worldMinY = worldMinY;
		this.worldMaxY = worldMaxY;
		List<Tier> list = new ArrayList<>();
		if (tall) {
			//          id       chain step y      biome                       name                 shape           thr   freq   city chorus rods  stal  dark  vault arena
			list.add(new Tier("e1", 1, 1, 384, Biomes.END_HIGHLANDS, "The Chorus Shelf", Shape.ISLANDS, -0.15, 1 / 60.0, 1, true, false, false, false, false, false));
			list.add(new Tier("e2", 1, 2, 640, Biomes.END_HIGHLANDS, "The Sky Harbour", Shape.ISLANDS, 0.0, 1 / 55.0, 2, false, false, false, false, false, false));
			list.add(new Tier("e3", 1, 3, 896, Biomes.SMALL_END_ISLANDS, "The Scattered Isles", Shape.ISLETS, 0.35, 1 / 18.0, 0, false, false, false, false, false, false));
			list.add(new Tier("e4", 1, 4, 1152, Biomes.END_MIDLANDS, "The Rod Groves", Shape.ISLANDS, -0.05, 1 / 50.0, 0, true, true, false, false, true, false));
			list.add(new Tier("e5", 1, 5, 1408, Biomes.END_BARRENS, "The Barren Reach", Shape.ISLANDS, 0.05, 1 / 70.0, 0, false, false, false, false, false, true));
			list.add(new Tier("e6", 1, 6, 1664, Biomes.END_HIGHLANDS, "The Grand Spire", Shape.ISLANDS, -0.1, 1 / 65.0, 6, false, false, false, false, false, false));
			list.add(new Tier("crown", 1, 7, 1920, Biomes.THE_END, "The Crown of the End", Shape.CROWN, 1.0, 1 / 60.0, 0, false, false, false, false, false, false));
			list.add(new Tier("u1", -1, 1, -384, Biomes.END_MIDLANDS, "The Hanging Roots", Shape.ISLANDS, -0.05, 1 / 55.0, 0, false, false, true, false, false, false));
			list.add(new Tier("u2", -1, 2, -896, Biomes.END_BARRENS, "The Purpur Crypt", Shape.CRYPT, 1.0, 1 / 60.0, 0, false, false, false, false, false, false));
			list.add(new Tier("u3", -1, 3, -1408, Biomes.SMALL_END_ISLANDS, "The Silent Drift", Shape.ISLETS, 0.3, 1 / 20.0, 0, false, false, false, true, false, false));
			list.add(new Tier("u4", -1, 4, -1920, Biomes.END_BARRENS, "The Void Root", Shape.ARENA, 1.0, 1 / 60.0, 0, false, false, false, false, false, false));
		}
		this.tiers = List.copyOf(list);
	}

	public static EndLayout byId(int id) {
		return id >= 2 ? LAYOUT_2 : LAYOUT_1;
	}

	// ------------------------------------------------------------------ tiers

	public List<Tier> ascent() {
		return this.tiers.stream().filter(Tier::ascent).toList();
	}

	public List<Tier> underside() {
		return this.tiers.stream().filter(t -> !t.ascent()).toList();
	}

	public @Nullable Tier tier(String id) {
		for (Tier t : this.tiers) if (t.id().equals(id)) return t;
		return null;
	}

	/** The next tier of the same chain (null after the Crown / U4). */
	public @Nullable Tier next(Tier tier) {
		for (Tier t : this.tiers) if (t.chain() == tier.chain() && t.step() == tier.step() + 1) return t;
		return null;
	}

	/** The previous tier of the same chain (null for E1 / U1: their Return Gate leads to the base platform). */
	public @Nullable Tier previous(Tier tier) {
		for (Tier t : this.tiers) if (t.chain() == tier.chain() && t.step() == tier.step() - 1) return t;
		return null;
	}

	public static boolean inZone(int z) {
		return z >= ZONE_MIN_Z && z <= ZONE_MAX_Z;
	}

	public static boolean inFightZone(int z) {
		return z >= FIGHT_MIN_Z && z <= FIGHT_MAX_Z;
	}

	/**
	 * The tier whose band holds {@code y} (tall layout, inside the tier zone), or null in the
	 * vanilla band. Bands run half way to the neighbouring tiers; the Crown's band starts at 1792.
	 */
	public @Nullable Tier tierAt(int y) {
		if (!this.tall || (y >= BASE_MIN_Y && y <= BASE_MAX_Y)) return null;
		Tier best = null;
		for (Tier t : this.tiers) {
			if ((y > BASE_MAX_Y) != t.ascent()) continue;
			if (best == null || Math.abs(t.y() - y) < Math.abs(best.y() - y)) best = t;
		}
		return best;
	}

	// ------------------------------------------------------------------ platforms and gates

	/** A gate platform: floor at {@code floorY}, centred on (strip centre x, {@code z}). */
	public record Platform(int floorY, int z, int gateZ, int arrivalZ) {}

	/** Where you arrive on a tier (its start platform) and the Return Gate next to it. */
	public Platform startPlatform(Tier tier) {
		int z = tier.startZ(), in = tier.dirIn();
		return new Platform(tier.y(), z, z - 2 * in, z + 2 * in);
	}

	/** The exit platform at a tier's far end and its Rift Gate up (Ascent) or down (Underside). */
	public Platform exitPlatform(Tier tier) {
		int z = tier.exitZ(), in = tier.dirIn();
		return new Platform(tier.y(), z, z + 2 * in, z - 2 * in);
	}

	/** The base platform's Ascent Gate (north) and Descent Gate (south); you arrive between them. */
	public static int ascentGateZ() {
		return BASE_PLATFORM_Z - 3;
	}

	public static int descentGateZ() {
		return BASE_PLATFORM_Z + 3;
	}

	public static BlockPos baseArrival(StripBounds bounds) {
		return new BlockPos(bounds.centerX(), BASE_PLATFORM_Y + 1, BASE_PLATFORM_Z);
	}

	public static BlockPos arrival(StripBounds bounds, Platform p) {
		return new BlockPos(bounds.centerX(), p.floorY() + 1, p.arrivalZ());
	}

	public static BlockPos gatePos(StripBounds bounds, Platform p) {
		return new BlockPos(bounds.centerX(), p.floorY() + 1, p.gateZ());
	}

	/** One gateway: where it stands and where it sends you. */
	public record Gate(String name, BlockPos pos, BlockPos target) {}

	/**
	 * Every gateway of the tall End: base Ascent/Descent Gates, each tier's Return Gate and Rift
	 * Gate. The E5 Rift Gate is listed too but only appears when the Void Stalker dies.
	 */
	public List<Gate> gates(StripBounds bounds) {
		List<Gate> gates = new ArrayList<>();
		if (!this.tall) return gates;
		int cx = bounds.centerX();
		BlockPos base = baseArrival(bounds);
		for (Tier tier : this.tiers) {
			Platform start = this.startPlatform(tier);
			if (tier.step() == 1) {
				BlockPos baseGate = new BlockPos(cx, BASE_PLATFORM_Y + 1, tier.ascent() ? ascentGateZ() : descentGateZ());
				gates.add(new Gate(tier.ascent() ? "ascent" : "descent", baseGate, arrival(bounds, start)));
			}
			Tier previous = this.previous(tier);
			BlockPos back = previous == null ? base : arrival(bounds, this.exitPlatform(previous));
			gates.add(new Gate(tier.id() + "_return", gatePos(bounds, start), back));
			Tier next = this.next(tier);
			if (tier.hasExit() && next != null) {
				Platform exit = this.exitPlatform(tier);
				gates.add(new Gate(tier.id() + "_rift", gatePos(bounds, exit), arrival(bounds, this.startPlatform(next))));
			}
		}
		return gates;
	}

	public @Nullable Gate gate(StripBounds bounds, String name) {
		for (Gate g : this.gates(bounds)) if (g.name().equals(name)) return g;
		return null;
	}

	// ------------------------------------------------------------------ special places

	/** The Void Stalker's arena (E5): around the exit platform, the whole strip width. Inclusive z range. */
	public int[] stalkerArenaZ(Tier tier) {
		int z = tier.exitZ(), in = tier.dirIn();
		int far = z + 6 * in, near = z - 16 * in;
		return new int[] {Math.min(far, near), Math.max(far, near)};
	}

	/** The closed arena of U4 (Phantom Matriarch) around its start platform. Inclusive z range. */
	public int[] matriarchArenaZ(Tier tier) {
		int z = tier.startZ(), in = tier.dirIn();
		int back = z - 6 * in, front = z + 26 * in;
		return new int[] {Math.min(back, front), Math.max(back, front)};
	}

	public static final int ARENA_HEIGHT = 16;

	/** U4: the chest that appears with the Void Key when the Matriarch dies (centre of the arena). */
	public BlockPos voidKeyChest(StripBounds bounds, Tier tier) {
		int[] zr = this.matriarchArenaZ(tier);
		return new BlockPos(bounds.centerX(), tier.y() + 1, (zr[0] + zr[1]) / 2 + 3 * tier.dirIn());
	}

	/** E4: the shulker-box vault (a purpur room in the middle of the tier). Centre of its floor block. */
	public BlockPos shulkerVault(StripBounds bounds, Tier tier) {
		return new BlockPos(bounds.centerX(), tier.y(), -850);
	}

	/** The z of a tier's end city start (E1, E2, E6). */
	public int cityZ(Tier tier) {
		return tier.city() == 2 ? -880 : tier.city() == 6 ? -820 : -720;
	}

	// The Crown (tier "crown"): a citadel on its foundation island, from the start platform along +z.
	public static final int CROWN_FLOOR_Y = 1920, CROWN_FOUNDATION_Y = 1888, CROWN_ROOF_Y = 1934, CROWN_SPIRE_TOP_Y = 1984;

	/** Crown hall z range (inclusive), the Void Seal wall and the vault behind it. */
	public static final int CROWN_MIN_Z = END_A - 6, CROWN_MAX_Z = END_A + 56, CROWN_SEAL_Z = END_A + 49;

	public static BlockPos hoardChest(StripBounds bounds) {
		return new BlockPos(bounds.centerX(), CROWN_FLOOR_Y + 2, CROWN_SEAL_Z - 5);
	}

	public static BlockPos vaultChest(StripBounds bounds) {
		return new BlockPos(bounds.centerX(), CROWN_FLOOR_Y + 1, CROWN_SEAL_Z + 4);
	}

	/** The two-high Void Seal door in the Crown's vault wall (lower block). */
	public static BlockPos voidSeal(StripBounds bounds) {
		return new BlockPos(bounds.centerX(), CROWN_FLOOR_Y + 1, CROWN_SEAL_Z);
	}
}
