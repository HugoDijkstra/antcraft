# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AntCraft is a Fabric mod for Minecraft **26.3** (unobfuscated Mojang names; Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Loom 1.19-SNAPSHOT, Java 25 target). It adds the *AntCraft Strip* world type. The overworld, Nether and End become narrow strips: 20×300 by default, configurable per new world. Each strip is cut into deep layers separated by bedrock floors with one hole each. The README lists the player-facing features.

## Commands

```sh
./gradlew build                                  # compile + unit tests; jar in build/libs/antcraft-1.0.<commits>.jar
./gradlew test --tests dev.antcraft.TallLayoutTest   # one test class (JUnit 5, pure geometry/tables only)
./gradlew runServer --args=nogui                 # dev server in run/ (port 25565, RCON 25575)
tools/worldcheck.sh [seed ...]                   # fresh worlds on a throwaway server, /antcraft selfcheck over RCON, fails on FAIL
python3 tools/analyze.py <world> <log>           # stdlib Anvil/NBT region analyzer (invariants of the strip)
```

- **Versions:** the version is `1.0.<git commit count>`, computed in `build.gradle` from `mod_version_base`. A local build and the GitHub release of the same commit therefore carry the same version.
- **In-game checks:** `/antcraft selfcheck` (ops) checks the loaded strip chunks of every strip dimension. `/antcraft audit ...` needs `-Dantcraft.dev=true`.
- **GameTest is not used:** GameTestServer can't load custom world presets. Test worldgen on a dedicated server with `level-type=antcraft\:strip` (tall) or `antcraft\:strip_classic`.

## Release pipeline: pushing ships to players

`.github/workflows/release.yml` builds every push to `master` and publishes a GitHub Release (`v1.0.N`) with the jar and a `.sha256`. The release is picked up automatically:
- **`AutoUpdater`** runs inside the mod. On each game or server start it downloads a newer release, checks the checksum and swaps the jar. It is skipped in dev, and the `autoUpdate` config option turns it off.
- **The user's dedicated server** (Docker container `antcraft` on the LAN host, files in `~/antcraft-server`) runs `update.py` from cron every 10 minutes. It installs new releases and restarts the server only when nobody is online.

So **a push to `master` is a release.** Push only finished, built work.

## Architecture

### Generators wrap vanilla
- **Wrapper generators:** `StripChunkGenerator` (overworld), `NetherStripChunkGenerator` and `EndStripChunkGenerator` each wrap a vanilla `NoiseBasedChunkGenerator`. They implement `StripGenerator` (the x/z footprint, `StripBounds`) and `WrappedNoiseGenerator`.
- **RandomState:** `NoiseBasedChunkGenerator` is final, and `ChunkMap` only builds a real `RandomState` for that class. `ChunkMapMixin` builds it for the wrappers.
- **Biome sources:** `StripBiomeSource`, `NetherStripBiomeSource` and `EndStripBiomeSource` wrap vanilla sources and supply the deep-layer biomes.
- **Lookups:** `Strip.bounds(level)` and `Strip.layout(...)` find a level's geometry through its generator. Mixins use them to tell whether they are in a strip world.

### Layouts: never change old ones
The vertical geometry is per world, saved in the generator codec's optional `layout` field. A missing field means the oldest layout.
- **`StripLayout`, overworld:**
  - **Layout 1 (classic):** vanilla at -64..320, 8 floors 256 apart, world -2032..335.
  - **Layout 2 (tall):** vanilla moved up to 1632..2015, 19 deep layers 196 apart (the Trial Vault layer is 128), world -2032..2031.
- **`NetherLayout`:** layouts 1 and 2 are the classic Nether. Layout 3 is the tall Nether: vanilla at 1904..2031, 19 deep layers 208 apart.
- **`EndLayout`:**
  - **Layout 1:** the classic End.
  - **Layout 2:** vanilla End at 0..255, with the Ascent tiers above it and the Underside tiers below, in the tier zone z -1400..-300. The fight zone, z -256..149, stays vanilla.
- **Per-layer content:** `LayerSpec` rows in `LayerTables` give each layer its stones, ground, trees, plants, biome, village and ores. `LayerTheme` holds only the behaviour flags.
- **Presets:** `world_preset/strip.json` is the tall layout and `strip_classic.json` the classic one. Both are added to the `minecraft:normal` world-preset tag, so vanilla Default is untouched.

**Hard invariant:** layout 1 (and Nether layouts 1/2, End layout 1) must keep generating bit-for-bit identically, because existing worlds generate new chunks from them.
- **Tests:** `LegacyGeometry` and `LayoutTablesTest` freeze the classic numbers.
- **Changes to old layouts:** only through `WorldUpgrade`'s chunk/world version steps. Those may only add things into open air or wake fluids.

### Tall layouts: the frame chunk
- **`FrameTerrain`:** runs the unchanged vanilla noise generator on a temporary `FrameProtoChunk`. Its height range is vanilla's, but its sections are the real chunk's sections `D` blocks higher. Noise, aquifers, surface rules and carvers write straight into the real chunk; nothing is copied.
- **After terrain:** features, structures and spawning run in real coordinates.
- **`WorldGenShift` + `VerticalAnchorAbsoluteMixin`/`WorldGenerationContextMixin` (`AnchorShiftHolder`):** add `D` to absolute height anchors. A handful of structure mixins cover vanilla's hard-coded heights: fortress, monument, mansion, ruined portal, slime, igloo, ocean ruin, jigsaw start height.
- **ThreadLocal exceptions:** `WorldGenShift` holds ThreadLocals for deep structures that must not be shifted. They are set only in try/finally on structure or feature threads, never across `buildTerrain`, which runs on another executor.

### Deep content
- **Terrain:** `DeepTerrain` and `DeepNetherTerrain` do the raw section writes during TERRAIN: floors, holes, halls, caves, forests, ores and arenas. Raw fluid writes are marked for post-processing so they flow.
- **Decoration:** trees, plants, the light grid, vines and leaf-distance recompute. Afterwards the wall clip re-asserts the barrier/bedrock walls over the 3×3 region.
- **Deep structures:** started by hand in `createStructures` with `Structure.generate` + `setStartForStructure`:
  - ancient city, villages, pyramid, temples, mineshafts, the Trial Vault (`VaultPlan` computes its way in and out);
  - their placement is in `DeepStarts` / `DeepNetherStarts`;
  - `JigsawStripMixin` restricts jigsaw free space to the strip so pieces fit instead of being sliced;
  - `VillageFarms` adds a farm to farm-less villages.
- **Runtime:**
  - **`ArenaManager`:** guardian boss rooms with a start button, gates, boss bar, decorations (`ThemeDecor`, `GuardianId`) and teleporters to the room above.
  - **Seals:** `SealBlock`/`VoidSealBlock` keyed gates (Nether, Ancient, Ember, Void keys).
  - **`EndArenas`:** the End bosses.
  - **`LayerTracker`:** layer titles and hints.
  - **Advancements:** the tall-only ones are removed outside tall worlds by `TallAdvancements`.
  - **Rescue tick:** in `AntCraft`, returns players outside the strip.
- **Gameplay mixins** (all deep-layer only, in strip worlds): mining hardness per layer, ore filtering, crop/sapling growth, spawn rate and spawn height near players, zombie gear, villager trade levels.

## Conventions that bite

- **Lithium is assumed:** the user runs Lithium, FerriteCore, Sodium, ImmediatelyFast and EntityCulling.
  - Never inject into methods Lithium `@Overwrite`s (e.g. `PortalForcer.findClosestPortalPosition`, `Level.getMinY/getMaxY/getHeight`, `NoiseBasedChunkGenerator.getSeaLevel`); wrap the call sites instead.
  - `antcraft.mixins.json` has `defaultRequire: 1`, so a broken target crashes startup.
  - Smoke-test with Lithium + FerriteCore in the server's `mods/`.
- **Vanilla API:** confirm every vanilla API against 26.3 sources (decompile the Loom merged jar). Names differ from older versions:
  - `EntityTypes.X`, `Identifier`;
  - `ColorCollection` for dyed blocks (`Blocks.CARPET.pick(DyeColor.RED)`);
  - `Blocks.IRON_CHAIN`;
  - `Feature#place(level, generator, random, pos)`.
- **Light-safe writes:** worldgen writes during TERRAIN go to sections directly; later stages use `level.setBlock`.
- **Commit messages** end with the `Co-Authored-By` line the session's instructions give.
