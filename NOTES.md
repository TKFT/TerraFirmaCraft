# River Delta Prototype — Implementation Notes

Branch: `feature/river-delta-prototype`. This file becomes the PR description.
Phases follow `RIVER_MOUTHS_BLENDTYPE_IMPLEMENTATION_PLAN.md`; locked design values
from `RIVER_DELTA_DESIGN.md`; sampler from `DELTA_NOISE_SAMPLER.md`.

---

## Phase 0 — Ground-truth verification

### Height pipeline order (CONFIRMED, `ChunkHeightFiller.sampleColumnHeightAndBiome`)

1. Weighted biome blend over `BiomeNoiseSampler.height()` (also partitions weights
   into normal / shore / ocean groups).
2. Shore transformation: `adjustHeightForShoreContributions` — per-`ShoreBlendType`
   weighted samplers.
3. Ocean tide-edge clamp when `oceanWeight >= 0.25` (pulls height toward
   `tideHeightNoise - 4` as land weight drops through 0.36 → 0.32).
4. `computeInitialRiverWeights` (biome → `RiverBlendType` weight array), then
   `sampleRiverInfo`, then `adjustHeightForRiverContributions` — per-blend-type
   weighted river samplers.
5. Volcanic (centered features) last, may override rivers.

**Terminal delta pass slots between (3) and (4)** — after shore + ocean clamp,
before the ordinary river carve — exactly the impl plan §4.4 slot. The DELTA
sampler is invoked out-of-band (never via `riverBlendWeights`, which is derived
purely from biome `riverBlendType()` — no biome maps to DELTA, so its weight is
structurally always 0 there).

### Deviations / discoveries vs. the implementation plan

1. **The density (`noise(y)`) pass needs its own out-of-band hook.**
   `ChunkNoiseFiller.calculateNoiseAtHeight` iterates river samplers by the same
   biome-derived `riverBlendWeights`; a weight-0 sampler is never called. The plan
   only describes the height-pass dispatch (§4.4). The delta sampler's boatable-roof
   carve therefore needs an explicit invocation in `calculateNoiseAtHeight`,
   blended as `noise = (1-w)*noise + w*delta.noise(y, postShoreNoise)` with the fan
   mask weight `w`, mirroring how weighted samplers compose. Also note: when
   `RiverInfo == null`, `adjustHeightForRiverContributions` force-sets
   `riverBlendWeights` to NONE=1.0 specifically so `noise()` is never called on
   uninitialized samplers — the delta hook must respect the same rule (only call
   `noise()` on columns where the height pass initialized the sampler).
2. **`RiverInfo` javadoc is stale**: claims width raw value clamped `[8, 18]`;
   actual bounds are `RiverEdge.MIN_WIDTH = 8`, `MAX_WIDTH = 24`
   (`AddRiversAndLakes.annotateRiverGridScale`: source edges start at 8, +2 per
   edge downstream, capped at 24). Width thresholds are keyed off 8/24.
3. **Terminal edge structure (CONFIRMED)**: `RiverEdge.drainEdge() == null` is the
   terminal test. Rivers are built *from the mouth upstream*
   (`AddRiversAndLakes.createInitialDrains`): the drain vertex of a terminal edge
   sits at a **shore region point** + 0.5 grid offset. `edge.drain()` is the
   sea-side vertex, `edge.source()` the upstream vertex. `MidpointFractal`
   bisection preserves both endpoints, so the rendered trunk terminates exactly at
   the graph drain; only the *biome* coastline drifts (quart-scale zoom), which is
   what the anchor march corrects for.
4. **Edge length**: one graph edge is ~2.7 grid (`RIVER_LENGTH`), shrinking ~0.92×
   per step upstream; a 4-bisection fractal has 16 sub-segments of ~0.17 grid each.
   The design doc's flavor claim that 100–135 blocks "equals 1–2 midpoint-fractal
   segments" is off (it's ~5–6 sub-segments); the locked block/grid dimensions are
   used as-is, the fractal-segment equivalence is ignored. (Flagged, not resolved.)
5. **`RegionPartition.Point.rivers()` (CONFIRMED)**: per 3-grid partition point,
   lists every edge whose center is within `1 + ceil(1.5 * 2.7) = 6` grid
   (`RiverEdge.MAX_AFFECTING_GRID_DISTANCE`, applied as a partition-rect). A major
   fan (≤ ~1.25 grid inland + ~1.75 lateral from a drain that lies ON the edge)
   is comfortably inside this envelope → the existing partition lookup suffices,
   no new spatial index. The ordinary river *search radius* (50 blocks in
   `sampleRiverEdge`) is the thing that's too short; the mouth resolver does its
   own partition scan for terminal edges.
6. **River biome overlay (CONFIRMED, two places)**:
   - Worldgen-visible biome: `BiomeSourceExtension.getBiomeExtension` overlays
     `TFCBiomes.RIVER` where `biome.hasRivers()` && fractal intersects within
     0.08 grid (~10 blocks).
   - Chunk-local biome for surface/features: `ChunkNoiseFiller.updateLocalCaches`
     sets `localBiomes` to RIVER where `height <= SEA+1 && normDistSq < 1.1 &&
     biomeAt.hasRivers()`.
   Both are gated on `hasRivers()`; all shore biomes use `.noRivers()` → both
   sites need the bounded mouth-channel exception (Phase 3).
7. **Flow/water (CONFIRMED)**: `ChunkNoiseFiller.sampleRiverData` samples 16×16
   per-block `RiverInfo` + 5×5 quart `Flow` (flow kept only where
   `normDistSq < 0.28`); `fillColumn` computes flow only when
   `localBiome.hasRivers()` — river water blocks are placed only where
   water + flow ≠ NONE + `y >= min(seaLevel-4, height)`. So distributary water
   placement needs: local biome override (see 6) + flow injection at the 5×5 quart
   flow grid + per-block `RiverInfo` (or mouth-channel equivalent).
8. **No stock worldgen-scale value exists.** `Settings` has no zoom/scale knob;
   grid→block scale is the fixed `Units.GRID_WIDTH_IN_BLOCK = 128`. The impl
   plan's "locate the Large Biomes scale source" resolves to: store geometry in
   grid units and multiply by a `worldgenScale` field (default 1.0) owned by the
   resolver — the single point an addon/large-biomes fork would set.
9. **Seed access (CONFIRMED)**: `RegionBiomeSource.initRandomState` receives
   `RegionGenerator`, which exposes `seed()` (the level `Seed`). The resolver
   derives all shape seeds from `regionGenerator.seed().seed()` + hashes of
   quantized drain coordinates. No identity hashes anywhere.
10. **Raw biome field for the anchor march**: `getBiomeExtensionNoRiver(quartX,
    quartZ)` (thread-safe `ConcurrentArea`). March step 0.125 grid = 4 quarts
    exactly, so the march samples the raw field at its native resolution boundary.
11. **Pre-existing working-tree experiment replaced.** The branch inherited an
    uncommitted, older `RiverNoise.delta` prototype (fan geometry + distributary
    tree computed *inside* the sampler). It contradicts the locked sampler design
    (mask/channels are resolver-owned; sampler is a pure radial profile) and was
    replaced by the `DELTA_NOISE_SAMPLER.md` version in Phase 2. Copy preserved
    outside the repo during development.

### Biome-set groundwork for the classifier (Phase 1)

From `TFCBiomes` as registered today:

- Eligible shore at anchor (locked, design §5): `SHORE`, `TIDAL_FLATS`;
  `COASTAL_DUNES` compact-tier only; everything else rejected (`EMBAYMENTS`
  explicitly reserved for ESTUARY).
- Shallow ocean ahead: `OCEAN`, `OCEAN_REEF`, `OCEAN_ATOLLS` (all floor −26..−8).
- Deep/forbidden in construction area: `DEEP_OCEAN`, `DEEP_OCEAN_TRENCH`,
  `DEEP_OCEAN_ATOLLS`, `OCEAN_RIDGE`, `OCEANIC_VOLCANIC_ARC` (volcanic ⇒ reject
  per compatibility matrix).
- Flat/low inland family (strict v1, design §5.1): `PLAINS`, `HILLS`, `LOWLANDS`,
  `SALT_MARSH`, `RIVER_VALLEY`, `LOW_CANYONS`, `MUD_FLATS`, `PATTERNED_GROUND`,
  `INVERTED_PATTERNED_GROUND`, `STONE_CIRCLES`, `KNOB_AND_KETTLE`,
  `DOLINE_PLAINS`, `CENOTE_PLAINS`.

### Debug tooling

`/tfc riverMouthDebug` (TEMPORARY — removed/gated before PR): dumps, for the
player's position: nearest terminal edges in the partition, their drain/source
grid coords, widths, and the raw (no-river) biome along the drain→sea march.
Added in Phase 0; grows mouth-context output in later phases.

---

## Phase 1 — Terminal context foundation

New package `net.dries007.tfc.world.river.mouth`:

- `DeltaTier` — COMPACT / NORMAL / MAJOR, width thresholds (14 / 17 / 22 blocks of
  terminal width — clearly tunable), locked grid-unit dimension ranges from
  RIVER_DELTA_DESIGN.md §2, and distributary count ranges for Phase 4.
- `RiverMouthContext`, `RiverMouthChannel`, `RiverMouthChannelSegment`,
  `RiverMouthChannelSample`, `RiverMouthSample` — records per the impl plan §3,
  plus a `DeltaTier` field on the context (the plan allows shaping the context for
  later extraction; tier is needed for the dunes-compact-only rule and Phase 4
  branch counts).
- `RiverMouthBiomes` — centralized biome-group predicates (eligible shores,
  shallow/deep oceans, flat inland family, anchor-march land/ocean categories).
- `RiverMouthClassifier` — strict pure function over a
  `RiverMouthClassificationInput` value; unit-tested across the condition matrix.
- `RiverMouthGeometry` — flow-aligned projection, wedge mask pinching from full
  fan half-width at the coast to ~1.25× trunk width at the inland tip, feathered
  boundary modulated by two-octave deterministic *sector noise* (pure function of
  shapeSeed + angle; never a smooth arc).
- `RiverMouthResolver` — terminal detection, coastline-anchor march (0.125-grid
  steps, 3.0-grid cap, raw biome field only, inland/seaward direction chosen by
  the drain's rendered category, drain fallback), classifier-input sampling,
  deterministic dimension jitter, and caching.
- `RiverMouthRandom` — SplitMix64-based hashing for all shape jitter.

Wiring: `BiomeSourceExtension.riverMouthResolver()` (default null) +
`RegionBiomeSource` creates the resolver in `initRandomState`. **No worldgen code
path calls it yet.** The resolver constructor reads `Seed.seed()` (a getter — it
does NOT consume `Seed.next()`), so the RNG stream feeding every existing noise
sampler is untouched → worldgen output is bit-identical by construction.

Deviations / decisions:

- Cache is `MapMaker().weakKeys()` (Guava, already a dependency) rather than a
  plain `ConcurrentHashMap`: `RiverEdge` instances are owned by the evicting
  region cache, and a strong-keyed map would leak evicted regions. Weak keys use
  identity — correct here — and a regenerated equal-coordinate edge resolves to a
  bit-identical context (unit-tested), so eviction can never affect output.
- "Ahead" classifier samples reach ~0.9 grid past the fan front: the rendered
  shore band can be wider than the seaward reach, so requiring open water
  immediately past the front would falsely reject legitimate mouths.
- Channel widths will be stored in grid units and scaled with `worldgenScale`
  like all other mouth geometry (consistent whole-mouth expansion). At stock
  scale this is exactly the trunk's block width.

---

## Phase 2 — `RiverBlendType.DELTA` + terminal-local dispatch

- `RiverNoise.delta` implemented exactly per DELTA_NOISE_SAMPLER.md §3 (style
  adaptation only). The pre-existing working-tree experiment (fan geometry inside
  the sampler) was discarded in favor of the locked design. `DELTA` added to
  `RiverBlendType` at the END of the enum — because sampler creation walks the
  enum in ordinal order off one stable seed stream, appending (not inserting)
  keeps every existing sampler's seeds identical to master.
- **No-biome-owns-DELTA enforced twice:** `BiomeBuilder.type(RiverBlendType)`
  throws on DELTA (compile-path guard), and `TFCChunkGenerator.initRandomState`
  scans `TFCBiomes.REGISTRY` at world load and throws if any registered extension
  (including addon-registered ones that bypass the builder) resolves to DELTA.
- Dispatch in `ChunkHeightFiller.adjustHeightForTerminalMouth`, between the
  shore/ocean stage and `computeInitialRiverWeights` (impl plan §4.4 slot). The
  DELTA sampler is invoked directly with `thisWeight` = fan mask weight and
  `info` = nearest mouth channel adapted to `RiverInfo` (while no channel network
  exists, the terminal trunk's own fractal is the mouth channel — distance
  computed from the fractal directly, NOT via the ordinary 50-block river search,
  so it works across the whole fan and through shore columns).
- Density pass: per the Phase 0 finding, `ChunkNoiseFiller.calculateNoiseAtHeight`
  gets an explicit delta hook after the weighted river loop:
  `noise = postShore + lerp(w, riverContribution, delta.noise(y, postShore))` —
  the delta replaces a mask-weighted share of the combined river contribution,
  reducing to exactly a full-weight blend-type at w = 1 and a no-op at w = 0.
- Tests: the six sampler tests from DELTA_NOISE_SAMPLER.md §6
  (`DeltaRiverNoiseTest`; the levee-vs-plain test probes deterministically for a
  wet-flat position since raised islands may legitimately exceed the levee crest),
  plus `DeltaWorldgenTest` — a headless twin of the `TFCChunkGenerator` height
  pipeline that runs two `ChunkHeightFiller` stacks (resolver on/off) on a fixed
  seed and asserts: a qualifying delta exists, terrain visibly changes (≥ 2
  blocks) inside its fan mask, and **every column outside the mask — the rest of
  the wide shore biome and all nonqualifying rivers included — is bit-identical**.
  This is the automated stand-in for the in-game Phase 2 gate.
- Master-equivalence note: on master-vs-branch (not just resolver on/off), all
  non-mouth terrain is also unchanged because (a) DELTA appends to the end of the
  enum (seed streams for existing samplers unchanged), (b) the registry scan in
  `initRandomState` consumes no RNG, and (c) the resolver reads `Seed.seed()`
  without consuming `next()`.

---

## Phase 3 — DIAGNOSTIC one-channel fan + trunk continuity

**This phase is a diagnostic milestone, not the feature** (per the design docs, a
one-channel fan never satisfies the delta definition — Phase 4 adds the real
distributaries).

- Resolver builds a single trunk channel per qualifying mouth: the terminal
  fractal's rendered course (source→drain is already upstream→seaward order),
  plus a seaward extension from the graph drain, parallel to the mouth axis, to
  just past the ocean-facing fan boundary — so the channel always reaches open
  water. Flow per segment uses `MidpointFractal.calculateFlow`'s angle convention.
- `.noRivers()` continuity, in the two places Phase 0 identified:
  - `BiomeSourceExtension.getBiomeExtension`: in `.noRivers()` biomes, the river
    biome overlay applies where the *resolved mouth channel* is within
    `max(0.08 grid, half channel width)` — bound to the specific terminal network
    via the resolver (a nearby unrelated river cannot trigger it).
  - `ChunkNoiseFiller.updateLocalCaches`: local biome becomes RIVER near a mouth
    channel (same `normDistSq < 1.1` rule as ordinary rivers), which also flips
    `forceCoastalSaltWater` off there;
    `sampleRiverData` injects mouth-channel `Flow` into the 5×5 quart flow grid
    (`normDistSq < 0.28`, matching ordinary flow), so flowing river water is
    placed through shore columns and out into the sea.
- Debug command now dumps resolved mouth contexts and the per-position fan/channel
  sample.
- Automated Phase 3 gates in `DeltaWorldgenTest` (shared fixture, one region scan):
  - trunk channel structure: exactly one branch-depth-0 channel, connected
    segments, downstream flow, seaward endpoint past the fan boundary;
  - **headless boat test**: every trunk-centerline column with fan weight ≥ 0.9 is
    carved to ≤ sea level − 2 through the shore into the sea (the ordinary river
    samplers are min-composed, so they can only deepen it);
  - river biome overlay follows the channel through `.noRivers()` quarts and does
    NOT leak sideways (2.5 channel-widths off-axis stays un-overlaid);
  - anchors sit on the rendered coastline (land within 0.25 grid inland,
    shore/ocean within 0.25 grid seaward) for every qualifying mouth found.
- The in-game boat test + screenshots remain for a human pass (I cannot drive a
  client); the checklist is at the end of this file.

---

## Phase 4 — Real distributaries (the feature definition)

Network construction in `RiverMouthResolver.buildChannels`, all jitter from
`shapeSeed` hashes with distinct salts:

- **Bifurcation apex** on the rendered trunk course (fractal point nearest a
  seeded 0.20–0.45 × inland-reach target), so the first split is visible from the
  river and connected to the real trunk course exactly.
- **Dominant channel** (index 0, branch depth 0): the fractal course up to the
  apex, then apex → mid → seaward-end with mild seeded bend, tapering to 0.85×
  trunk width. This is the boat route — it keeps full trunk depth via the
  width-scaled sampler.
- **Secondaries** (branch depth 1): split from *staggered* seeded points along
  the dominant path (t = 0.15–0.60) — not a single radial apex — each with two
  bent segments to the ocean-facing boundary, widths 0.40–0.60 × trunk (unequal,
  ≥ 4 blocks), tapering to 0.6× by the mouth. Lateral endpoint slots alternate
  flanks in disjoint magnitude bands (0.42–0.57, 0.80–0.95 × fan half-width) with
  per-mouth handedness flip, so same-side channels keep ≥ ~0.23 half-widths of
  island between them.
- Counts (locked table): compact 2; normal 2–4; major 3–4 with a 12% fifth.
- Carve/flow/water/biome-overlay all ride the Phase 3 plumbing unchanged — the
  sampler consumes the nearest channel as `RiverInfo` (per-channel width ⇒
  per-channel depth/levee automatically), flow comes from channel segments,
  water + overlay from the channel-distance rules. No "painted strips": every
  channel is carved, flowing, and water-bearing by construction of that plumbing.
- New/updated automated gates: network structure (counts in tier range,
  connectivity of every secondary onto the dominant path, downstream flow, every
  endpoint past the seaward boundary, unequal widths), islands ≥ sea−2 between
  adjacent well-separated channels just seaward of the coastline, boat test still
  on the dominant channel, determinism via context equality across resolver
  instances (channels included).
- **Overlap priority fix (found by the islands gate):** the ordinary river pass
  was re-carving the mouth edge's *replaced* course below the bifurcation apex,
  cutting a ghost waterway through islands. Fix per impl plan §4.5 / sampler doc
  §5: inside the fan mask the mouth network owns river behavior — the ordinary
  height carve lerps out by the mask weight (mirroring the density-pass
  composition), and the mouth's own edge is excluded from the ordinary river
  biome overlay and quart flow (channel network supplies both). Unrelated rivers
  keep normal behavior; outside masks everything is bit-identical as before.

---
