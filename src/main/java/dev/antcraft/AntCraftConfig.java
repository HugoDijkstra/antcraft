package dev.antcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/antcraft.json}. Read once at start-up (written with the defaults if missing) and
 * changed from the Mod Menu screen. The strip size only applies to worlds created afterwards: each
 * world stores its own size with its generator settings.
 */
public final class AntCraftConfig {
	/** Width of the strip (x) for new worlds, {@link StripBounds#MIN_WIDTH}..{@link StripBounds#MAX_WIDTH}. */
	public int stripWidth = StripBounds.DEFAULT_WIDTH;
	/** Length of the strip (z) for new worlds, {@link StripBounds#MIN_LENGTH}..{@link StripBounds#MAX_LENGTH}. */
	public int stripLength = StripBounds.DEFAULT_LENGTH;
	/**
	 * Extra mining time per deep layer for pickaxe blocks: layer N takes (1 + N * this) times as long.
	 * 0 = off. The server's value applies; it is sent to the clients ({@link MiningHardness}).
	 */
	public double miningHardnessPerLayer = 0.5;
	public boolean layerTitles = true;
	public boolean spawnMobsNearPlayers = true;
	public boolean guardians = true;
	public boolean netherKeyGate = true;
	public boolean ancientKeyGate = true;
	/** Tall worlds: seal floor 16's hole until an Ember Key (from the Nether's Ember Forge) is used. */
	public boolean emberKeyGate = true;
	/** Download new AntCraft releases from GitHub on start ({@link AutoUpdater}). */
	public boolean autoUpdate = true;

	public static final double MAX_HARDNESS_PER_LAYER = 2.0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile AntCraftConfig instance = new AntCraftConfig();

	public static AntCraftConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(AntCraft.MOD_ID + ".json");
	}

	/** Loads the config file, creating it with the defaults if it does not exist. */
	public static void load() {
		Path path = path();
		AntCraftConfig config = new AntCraftConfig();
		boolean write = true;
		if (Files.exists(path)) {
			try {
				AntCraftConfig read = GSON.fromJson(Files.readString(path), AntCraftConfig.class);
				if (read != null) config = read;
			} catch (IOException | JsonParseException e) {
				// keep the user's file as it is, so a typo doesn't wipe their settings
				AntCraft.LOGGER.error("AntCraft: could not read {}, using the defaults", path, e);
				write = false;
			}
		}
		config.sanitize();
		instance = config;
		if (write) config.save(); // adds missing fields, rewrites clamped values
		AntCraft.LOGGER.info("AntCraft config: strip {}x{}, mining hardness {} per layer", config.stripWidth, config.stripLength, config.miningHardnessPerLayer);
	}

	/** Makes this the active config and writes it to disk. */
	public void apply() {
		this.sanitize();
		instance = this;
		this.save();
		MiningHardness.resync(); // a world this client is hosting uses the new value right away
	}

	public void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this) + "\n");
		} catch (IOException e) {
			AntCraft.LOGGER.error("AntCraft: could not write {}", path, e);
		}
	}

	public AntCraftConfig copy() {
		AntCraftConfig copy = GSON.fromJson(GSON.toJson(this), AntCraftConfig.class);
		copy.sanitize();
		return copy;
	}

	private void sanitize() {
		this.stripWidth = Math.clamp(this.stripWidth, StripBounds.MIN_WIDTH, StripBounds.MAX_WIDTH);
		this.stripLength = Math.clamp(this.stripLength, StripBounds.MIN_LENGTH, StripBounds.MAX_LENGTH);
		if (!Double.isFinite(this.miningHardnessPerLayer)) this.miningHardnessPerLayer = 0.5;
		this.miningHardnessPerLayer = Math.clamp(this.miningHardnessPerLayer, 0.0, MAX_HARDNESS_PER_LAYER);
	}
}
