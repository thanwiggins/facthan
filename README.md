# Facthan

A Minecraft Forge mod that turns structure generation into a deterministic "kingdom generator."
Once per world, before any chunk ever generates, Facthan picks a set of factions, force-generates
each one's **capital** structure at a location that satisfies your distance rules and actually
passes real structure generation (not just a biome check) - no exceptions - then scatters a random
set of that faction's other ("supporting") structures around the capital in a realm. Every other
registered faction - one that wasn't picked as a capital-faction this world - becomes an "orphan"
faction instead: each of its unique supporting structures is force-generated exactly once, spread
out from capitals and each other by its own configurable distance rules. Only structures with no
faction at all are left to vanilla's ordinary `random_spread`.

This is a hard departure from a purely-computed "political map" - a capital's and a realm's exact
locations are decided once and persisted, so they survive server restarts.

## Features

- **Guaranteed capital placement** - drives the same structure-generation code vanilla's own
  `/place structure` command uses, so validating a location ("does this actually fit here?") and
  placing it happen in one step, independent of the normal `structure_set` spacing pipeline.
- **Realm building** - each capital gets a random number of non-capital structures from its
  faction scattered in an annulus around it, each respecting a minimum separation from the others.
- **Seed-flush, done safely** - if valid capital locations can't be found after repeated tries on
  one seed, the incomplete world is discarded and silently recreated with a new seed instead of
  either generating a half-decided kingdom or crashing the game; this is bounded, so a genuinely
  impossible configuration still surfaces as a real error instead of retrying forever.
- **Orphan structure generation** - a registered faction that isn't chosen as a capital this world
  still gets each of its unique supporting structures force-generated exactly once, subject to its
  own distance rules from capitals, other supporting structures, and the world origin. Every
  registered faction's structures are handled deterministically by Facthan either way - none are
  left to vanilla's `random_spread`. Pair this with a mod like Structurify to also disable normal
  generation for these structure_sets; Facthan's own veto (see below) is a backstop, not the
  primary guard against duplicates.
- **Roads** - once a capital's realm is fully placed, Facthan builds a terrain-following road from
  the capital out to each of its own supporting structures, ending at whichever real-world-facing
  side of each structure was registered as its "front" (see Designating a structure's front below).
  A structure with no registered front simply gets no road; a road that can't find a route within
  its search budget is silently skipped rather than blocking world creation. A road has a clean,
  two-tone cross-section (a plain inner surface with a distinct outer border, both single global
  block choices - see the config bullets below) and a slope-limited grade; a road that crosses a gap
  (water, a ravine) always has a solid, continuous surface to walk on, supported underneath by
  periodic piers reaching all the way down to the real ground beneath, like a real beam bridge.
- **Optional Worldborder integration** - if
  [Than's Worldborder](https://github.com/thanwiggins/worldborder) is installed and its custom
  overworld border is enabled, the capital search stays inside it; otherwise it falls back to a
  configurable default radius.
- **Mod-agnostic** - operates purely on `structure_set` placement and vanilla structure generation,
  so it works with any structure from any mod.

## Requirements

- Minecraft 1.20.1
- Forge 47.3.0+

No other dependencies - Than's Worldborder is optional and only changes the search's world-border
bound when present.

## Installation

1. Install Forge for Minecraft 1.20.1.
2. Drop `facthan-<version>.jar` into your `mods` folder.
3. Launch the game once to generate `config/facthan-common.toml`.
4. Define your factions and their structures via a datapack (see Configuration below).
5. Create a new world. The capital search runs automatically on the "Building world" loading
   screen, before any spawn chunk generates - nothing else to trigger.

The mod does nothing to a world unless at least one faction has an `is_capital` structure_set
registered (see below).

## Configuration

### `config/facthan-common.toml`
A COMMON-type config, not CLIENT - the capital/realm search only ever runs on whichever process is
actually generating the world (the dedicated server, or the integrated server in singleplayer), so
only that process's own copy of these settings can ever matter.

- `minCapitalCount` / `maxCapitalCount` (default `3` / `3`) - how many factions get a capital this
  world - a random value in this range (inclusive) is rolled once per world. Clamped down to
  however many factions actually have an `is_capital` structure_set registered, if fewer.
- `minDistanceFromOrigin` (default `250`) - minimum distance, in blocks, a capital may be from
  the world origin.
- `minDistanceBetweenCapitals` (default `500`) - minimum distance, in blocks, a capital may be
  from every other capital.
- `minSupportingStructures` / `maxSupportingStructures` (default `3` / `5`) - how many additional
  structures are placed in each realm.
- `minSupportingStructureRange` / `maxSupportingStructureRange` (default `150` / `250`) - how far
  a supporting structure may be from its capital.
- `minSupportingStructureSeparation` (default `200`) - minimum distance a supporting structure must
  be from every other supporting structure already placed in the same realm.
- `minOrphanStructureDistanceFromOrigin` (default `250`) - minimum distance an orphan structure
  (a supporting structure force-generated once for a faction that didn't get a capital this world)
  may be from the world origin. Matches `minDistanceFromOrigin`'s default.
- `minOrphanStructureDistanceFromCapitals` (default `500`) - minimum distance an orphan structure
  may be from every capital.
- `minOrphanStructureDistanceFromSupportingStructures` (default `250`) - minimum distance an orphan
  structure may be from every other supporting structure already placed, whether in a capital's
  realm or by another orphan faction.
- `fallbackWorldBorderRadius` (default `1000`) - used only when Than's Worldborder isn't installed
  or its custom overworld border is disabled.
- `orphanPriorityFactions` (default `["valarian_conquest:neutral"]`) - faction ids that get first
  pick of space among orphan factions, in the order listed, ahead of every other orphan faction in
  their normal order - and get 20 location retries per structure instead of the usual 5, so they're
  also meaningfully more likely to actually find a spot, not just first in line with the same odds
  as everyone else. Only matters when space is tight enough that not every orphan faction's
  structures can find room.
- `enableRoads` (default `true`) - whether to build roads at all (see Roads below).
- `roadAnchorOffset` (default `-8`) - how many blocks in front of a structure's own bounding box its
  road actually ends. Negative values let the road continue into the bounding box instead (e.g.
  through a doorway) - since the structure is already force-generated by the time roads are
  painted, this will overwrite some of the structure's own blocks near its entrance.
- `roadWidth` (default `4`) - width, in blocks, of a road's paved surface.
- `roadInnerBlock` (default `minecraft:dirt_path`) - block used for a road's inner (non-border)
  surface.
- `roadOuterBlock` (default `minecraft:stone_bricks`) - block used for the one-block border on each
  edge of a road's surface.
- `roadBridgeBlock` (default `minecraft:oak_planks`) - block used for a bridge's support piers. The
  road's own surface is always solid and continuous regardless - this is only the support underneath
  it.
- `roadBridgePierInterval` (default `3`) - how often, in blocks along a bridge, a support pier
  drops from each of the road's two outer edges down to solid ground - every other column in the
  gap is left as a bare floating deck (the deck surface itself stays solid and continuous either
  way), like a real beam bridge's periodic edge piers rather than a solid wall filling the entire gap.
- `roadBridgePierMaxHeight` (default `20`) - the tallest a single pier is allowed to drop before
  giving up, so a pier over a very deep gap doesn't descend forever.
- `roadMaxSlopeRise` / `roadMaxSlopeRun` (default `1` / `2`) - the steepest grade a road's smoothed
  elevation is allowed to change at, expressed as blocks of height change per blocks traveled.

### Defining factions
Register a faction with a `data/<namespace>/political_factions/<id>.json` file in any datapack:
```json
{ "display_name": "Kingdom of Embers", "color": "#B33A2E" }
```
The file's own location (`<namespace>:<id>`) is the faction's id. Any number of datapacks can each
contribute factions.

### Designating a faction's structures
Use the `facthan:faction_spread` placement type in a `structure_set` JSON instead of
`minecraft:random_spread`, adding two fields - `faction` (as before) and `is_capital`:
```json
{
  "structures": [{ "structure": "<namespace>:throne_hall", "weight": 1.0 }],
  "placement": {
    "type": "facthan:faction_spread",
    "salt": 12345,
    "spacing": 1,
    "separation": 0,
    "faction": "<namespace>:<id>",
    "is_capital": true
  }
}
```
Give each faction exactly one `is_capital: true` structure_set (its capital) and as many
`is_capital: false` (the default, so the field can simply be omitted) structure_sets as you like
(its supporting structures - repeats across a single realm are allowed). If this faction is
selected as a capital-faction this world, every one of these structure_sets is placed *only* by
the capital/realm search. If it's *not* selected, it becomes an orphan faction instead: each of its
unique supporting structures is placed once by the orphan-generation routine. Either way, normal
generation is vetoed for all of this faction's structure_sets, no exceptions - Facthan places every
registered faction's structures itself.

`spacing`, `separation`, `salt`, `frequency`, `locate_offset`, and `spread_type` are still inherited
from `random_spread` but no longer affect where a faction's structures actually generate, since
normal generation is vetoed for every registered faction either way - they're only meaningful if you
remove a faction's registration entirely (see above), leaving its structure_sets to plain vanilla
behavior. `exclusion_zone` is likewise inherited but unused by this mod's own placement logic.

### Designating a structure's front
Roads need to know which side of a structure its entrance is on - register it with a
`data/<namespace>/structure_fronts/<path>.json` file, where `<namespace>:<path>` matches a
`worldgen/structure` id:
```json
{ "direction": "south" }
```
This is the structure's *local*, unrotated front (i.e. as authored in its own NBT/template) - most
structures Facthan places are jigsaw structures that get a random real-world rotation each time
they generate, so Facthan combines this local direction with whichever rotation was actually rolled
to find the true real-world-facing side before building a road to it. A structure with no matching
file here simply never gets a road; this is mod-agnostic like everything else in Facthan, so any
datapack can register a front for any structure, not just the bundled Valarian Conquest ones.

## Mod Interactions

**Than's Worldborder** ([github.com/thanwiggins/worldborder](https://github.com/thanwiggins/worldborder)) -
optional. When installed with its custom overworld border enabled, the capital search stays inside
that bound instead of `fallbackWorldBorderRadius`.

## License

Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - share, modify, and
redistribute freely, including commercially, as long as you credit ThanWiggins.
