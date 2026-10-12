# Survival world: the studio HQ in a real world

Status: implemented on branch `usage-display` (mod + launchers + docs) and played in game
(2026-10-12): the site finder, the ground blend, the farms, pens, bedroom, doors and lighting
were checked by the user in a survival world; the fixes that came out of that (fence height,
frozen channels, floating tall grass, the raised strip between the farms, trees near the farms,
climbable blocks in the pens) are in. Not yet checked: `dev.hq.check` and the cameras from a
natural site.

## Why

The studio world is a still life for watching agents: creative, superflat, frozen golden hour,
no mobs. A second kind of user wants to *play* Minecraft while the team codes, with the same HQ
as the base. Those are different worlds, not a setting on one world, so each is its own save and
the choice is made at launch.

## What

- `world.WorldProfile`: `STUDIO` ("AgentCraft HQ") and `SURVIVAL` ("AgentCraft Survival").
  Chosen with `AGENTCRAFT_WORLD` (launchers: `-World` / `--world`), remembered per world in
  `agentcraft-world.json` (`profile`). `HqWorld.profileOf` is the one place that answers "which
  kind of world is this"; `isHq` means "either".
- Creation (`client.AutoWorld`): studio as before (fixed seed, flat meadow, creative, peaceful,
  no structures). Survival: `WorldPresets.createNormalWorldDimensions`, structures on, survival,
  normal difficulty, commands allowed, random seed unless `AGENTCRAFT_WORLD_SEED`.
- Rules (`HqWorld.applyRules(server, profile)`): the studio keeps its still-life and
  HQ-protecting rules. Survival is vanilla: every rule the studio touches is reset to the vanilla
  default on each start (values read from `new GameRules(enabledFeatures)`), and players keep
  their game mode. Creepers can therefore damage the HQ; `/agentcraft hq` rebuilds it.
- Placement: the studio plan is drawn in design coordinates (ground y=64 at the origin) and
  applied at an `HqSite` (world x, ground y, z of the design origin). `Plan` carries the offset
  and translates in `apply` (chunks, bindings, drop clearing); `Anchors.Builder.offset` shifts
  every anchor and the bounds; the stored plan's box is in world coordinates. The studio world
  uses offset 0 and is byte-for-byte the same plan as before.
- Site (`hq.HqTerrain.findSite`): lattice candidates around the world spawn, judged on the
  ground heights of the core footprint (median = ground level; mean deviation, range, water
  share) and, more loosely, of the whole box. Kept in the marker file as `site`, so rebuilds
  land on the same ground.
- Ground (`HqTerrain.sample` + `HqLandscape.naturalGround`): the world's ground under every
  column is read first; then every column is rewritten at a blend of the world's and the
  design's height: outermost 2 columns kept natural (`Plan.keepColumn`, null cells, never
  written), 16 columns of blend, level areas always at design height, shores never below the
  water they touch. The natural box reaches 16 below the meadow.
- Survival extras (`Options.survival`): entrance doors on a mullion with a glazed transom (just
  outside the agents' bounds); a workbench corner in the hall's south-east bays; a lean-to
  bedroom on the east terrace with doors from the hall and to the meadow (the glazed conservatory
  on the west terrace, the garden wall and gate at the lychgate and the joined-up ring path are
  in both worlds); a crop farm and four
  animal pens behind the hall (level areas added to the landscape only in survival plans; two of
  each animal spawned after the build, top-up only); invisible light blocks every 6 columns above
  everything open to the sky (plus under canopies), and the same lattice one block above the
  world's ground in an 18-block margin around the site, the only thing written there apart from
  the farm clearing: kept columns within 6 of a farm have their trees felled on apply
  (`Plan.clearCanopy`, before the plan's own writes), and no planned tree stands that close. Door
  and gate `open`/`powered`, furnace `lit`, crop `age` and farmland `moisture` join the "driven"
  properties a rebuild ignores. The farm fences stand on the meadow (`FLOOR`), not at the hall's
  `FEET`; the first build had them one block up.
- An existing survival world only builds the HQ once; features added later (farms, bedroom) need
  `/agentcraft hq` in that world, which applies them as a diff.

## Not done / known

- First survival launch (2026-10-12) built the HQ at y -65: in 26.3 both motion-blocking
  heightmaps are `Heightmap.Usage.CLIENT`, so `getHeight` on the server reads every column as
  empty. `HqTerrain.column` now walks the column itself from the chunk's highest filled section.
  Since then the finder has chosen well on the seeds tried (e.g. y 95, 69 blocks from spawn, flat
  to within two blocks, dry). Still worth a look on more seeds: the blend band on steeply sloped
  ground, that water at the edge stays put, and the doors from `cam_entrance_atrium`.
- Ground between two level areas blends on its own: the three columns between the farms came out
  a block high until both farms became one level rectangle. Any new pair of level areas that
  nearly touch wants the same treatment.
- Cold biomes freeze still water: the farm channels are waterlogged light blocks for that reason
  (`St.unfrozenWater`); the pond is plain water and will freeze in a cold site, which is fine.
- The ring of hills is mostly blended away in a natural world (it sat at the box edge by
  design); the world's own horizon replaces it.
- A natural tree straddling the kept band loses its inner half; leaves decay, a log column may
  stay. Villages or other structures inside the box are replaced.
- The first start of a survival world is slower: the finder reads a few hundred columns around
  the spawn (generating ~150-200 chunks), then the build reads 8.5k columns.
- `/agentcraft hq test` is refused on a natural site (the test room builds at the origin).
- DevBridge camera moves still hand the player back to creative after a spectator shot; in a
  survival world that is a dev-only convenience, not play.
- Agent entities are client-side; mobs ignore them and they ignore mobs.
