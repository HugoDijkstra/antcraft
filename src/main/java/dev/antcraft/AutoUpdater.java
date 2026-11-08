package dev.antcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Keeps AntCraft up to date from its GitHub releases (github.com/HugoDijkstra/antcraft, built by
 * the repo's GitHub Actions workflow).
 *
 * <p>On start, a background thread asks GitHub for the latest release. If its version is newer than
 * the running one, the jar is downloaded next to the running jar, checked against the release's
 * {@code .sha256} file, and swapped in: on Linux/macOS right away (the running game keeps using the
 * old file it has open), on Windows (where the open jar is locked) by a small helper started when
 * the game exits. The new version is used from the next start; players are told in chat.
 *
 * <p>Skipped in the development environment and when {@link AntCraftConfig#autoUpdate} is off.
 * Only ever downloads from this repository's releases, over HTTPS.
 */
public final class AutoUpdater {
	private AutoUpdater() {}

	private static final String REPO = "HugoDijkstra/antcraft";
	private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
	private static final String USER_AGENT = "antcraft-updater";
	private static final Pattern ASSET = Pattern.compile("antcraft-[0-9][0-9A-Za-z.+-]*\\.jar");

	private static volatile @Nullable Component notice;

	public static void start() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Component message = notice;
			if (message != null) handler.player.sendSystemMessage(message);
		});
		if (FabricLoader.getInstance().isDevelopmentEnvironment() || !AntCraftConfig.get().autoUpdate) return;
		Thread thread = new Thread(AutoUpdater::run, "AntCraft updater");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		try {
			check();
		} catch (Exception e) {
			AntCraft.LOGGER.warn("AntCraft updater: update check failed ({})", e.toString());
		}
	}

	private static void check() throws IOException, InterruptedException, NoSuchAlgorithmException {
		ModContainer mod = FabricLoader.getInstance().getModContainer(AntCraft.MOD_ID).orElseThrow();
		String current = mod.getMetadata().getVersion().getFriendlyString();
		Path jar = mod.getOrigin().getPaths().stream().filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).findFirst().orElse(null);
		if (jar == null) return; // not running from a jar
		Path dir = jar.getParent();
		cleanUp(dir);

		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpResponse<String> response = http.send(
			request(LATEST).header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			AntCraft.LOGGER.info("AntCraft updater: no release found (HTTP {})", response.statusCode());
			return;
		}
		JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
		String latest = release.get("tag_name").getAsString().replaceFirst("^v", "");
		if (compare(latest, current) <= 0) {
			AntCraft.LOGGER.info("AntCraft updater: {} is up to date (latest release {})", current, latest);
			return;
		}

		String jarUrl = null, shaUrl = null, jarName = null;
		for (JsonElement element : release.getAsJsonArray("assets") == null ? new JsonArray() : release.getAsJsonArray("assets")) {
			JsonObject asset = element.getAsJsonObject();
			String name = asset.get("name").getAsString();
			String url = asset.get("browser_download_url").getAsString();
			if (ASSET.matcher(name).matches() && !name.contains("-sources")) {
				jarName = name;
				jarUrl = url;
			} else if (name.endsWith(".jar.sha256")) {
				shaUrl = url;
			}
		}
		if (jarUrl == null || shaUrl == null || !jarUrl.startsWith("https://github.com/" + REPO + "/releases/download/")) {
			AntCraft.LOGGER.warn("AntCraft updater: release {} has no usable jar + checksum", latest);
			return;
		}

		AntCraft.LOGGER.info("AntCraft updater: downloading {} (running {})", latest, current);
		String expected = http.send(request(shaUrl).build(), HttpResponse.BodyHandlers.ofString()).body().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
		Path download = dir.resolve(jarName + ".download");
		HttpResponse<InputStream> body = http.send(request(jarUrl).build(), HttpResponse.BodyHandlers.ofInputStream());
		if (body.statusCode() != 200) throw new IOException("download failed: HTTP " + body.statusCode());
		try (InputStream in = body.body()) {
			Files.copy(in, download, StandardCopyOption.REPLACE_EXISTING);
		}
		String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(download)));
		if (!actual.equals(expected)) {
			Files.deleteIfExists(download);
			AntCraft.LOGGER.error("AntCraft updater: checksum mismatch for {}, update discarded", jarName);
			return;
		}

		Path target = dir.resolve(jarName);
		if (swapNow(jar, download, target)) {
			AntCraft.LOGGER.info("AntCraft updater: installed {}; it is used from the next start", jarName);
		} else {
			swapOnExit(jar, download, target);
			AntCraft.LOGGER.info("AntCraft updater: {} downloaded; it is installed when the game closes", jarName);
		}
		notice = Component.literal("AntCraft " + latest + " was downloaded. Restart Minecraft to use it.").withStyle(ChatFormatting.GREEN);
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", USER_AGENT);
	}

	/** Linux/macOS: an open jar can be deleted; the running game keeps its open copy. */
	private static boolean swapNow(Path running, Path download, Path target) {
		try {
			Files.move(download, target.resolveSibling(target.getFileName() + ".new"), StandardCopyOption.REPLACE_EXISTING);
			Path staged = target.resolveSibling(target.getFileName() + ".new");
			Files.delete(running); // fails on Windows while the jar is open
			Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			// put the download back for the exit helper
			Path staged = target.resolveSibling(target.getFileName() + ".new");
			try {
				if (Files.exists(staged)) Files.move(staged, download, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {}
			return false;
		}
	}

	/** Windows: the jar stays locked until the JVM exits, so a detached helper swaps it a few seconds later. */
	private static void swapOnExit(Path running, Path download, Path target) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				String script = "ping -n 6 127.0.0.1 >nul & del /f /q \"" + running + "\" & move /y \"" + download + "\" \"" + target + "\"";
				new ProcessBuilder("cmd", "/c", script).start();
			} catch (IOException e) {
				AntCraft.LOGGER.warn("AntCraft updater: could not start the install helper ({})", e.toString());
			}
		}, "AntCraft updater install"));
	}

	/** Leftovers of an interrupted download. */
	private static void cleanUp(Path dir) {
		try (var files = Files.list(dir)) {
			files.filter(p -> {
				String name = p.getFileName().toString();
				return name.startsWith("antcraft-") && (name.endsWith(".jar.download") || name.endsWith(".jar.new"));
			}).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {}
			});
		} catch (IOException ignored) {}
	}

	/** Compares dotted numeric versions ("1.0.12" vs "1.0.9"); non-numeric parts compare as 0. */
	static int compare(String a, String b) {
		String[] x = a.split("[.+-]"), y = b.split("[.+-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int parse(String part) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
