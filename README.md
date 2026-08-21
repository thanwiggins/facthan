# Facthan

A Minecraft Forge mod that turns structure generation into a political map. Structures can be
assigned a **faction**, and faction-owned structures generate only inside dense, contiguous
"kingdom" territories with an empty no-man's-land buffer between them - instead of being
scattered evenly across the world the way vanilla `random_spread` placement normally works.
Every world seed produces a completely unique set of kingdom borders, computed purely from that
seed. Nothing is ever persisted to disk - the political map for a given seed is always
recomputable, identically, on demand.

This mod only ever acts as a *political* check. It has no opinion on biome suitability or any
other placement rule - those all run exactly as they would without this mod installed, whether
they come from vanilla or another structure mod.

## Features

- **Voronoi kingdoms** - a jittered-grid cellular (Voronoi/Worley) noise, seeded from the world
  seed, deterministically partitions the map into organic region cells. No biome tags, no
  hardcoded regions - a pure function of the seed.
- **Hard borders with buffer zones** - a faction structure that would generate too close to a
  kingdom's border simply doesn't generate at all, so kingdoms read as dense, defined centers
  surrounded by contested wilderness rather than fading gradients.
- **Structures with no faction are untouched** - opting a `structure_set` into this system is
  per-structure-set and explicit; everything else generates exactly as it always has.
- **Mod-agnostic** - works with any structure from any mod, since it operates purely on
  `structure_set` placement, not on any mod-specific structure logic.
- **Queryable** - `/political` reports which faction (if any) owns a position and how close it is
  to a border, giving a stable, seed-derived contract for future consumers (e.g. a map-mod overlay)
  without needing anything written to disk.

## Requirements

- Minecraft 1.20.1
- Forge 47.3.0+

No other dependencies - this mod is fully self-contained.

## Installation

1. Install Forge for Minecraft 1.20.1.
2. Drop `facthan-<version>.jar` into your `mods` folder.
3. Launch the game once to generate `config/facthan-common.toml`.
4. Define at least one faction and opt a `structure_set` into it (see Configuration below), then
   set `politicalMapEnabled = true`.

The mod does nothing at all until both a faction is registered and `politicalMapEnabled` is
turned on.

## Usage

| Command | Effect |
|---|---|
| `/political` | Reports which faction owns your current position, and its distance to the nearest border. |
| `/political <x> <z>` | Same, for an arbitrary column. |

## Configuration

### `config/facthan-common.toml`
A COMMON-type config, not CLIENT - structure placement only ever runs on whichever process is
actually generating the chunk (the dedicated server, or the integrated server in singleplayer), so
only that process's own copy of these settings can ever matter.
- `politicalMapEnabled` (default `false`) - master switch for the whole system.
- `cellSize` (default `4000`) - average width, in blocks, of one kingdom before jitter. Larger
  values produce fewer, bigger kingdoms; smaller values produce more, smaller ones.
- `borderBufferWidth` (default `200`) - width, in blocks, of the no-man's-land straddling every
  border. A faction structure whose approximate distance to the nearest border is under this value
  never generates.

### Defining factions
Register a faction with a `data/<namespace>/political_factions/<id>.json` file in any datapack:
```json
{ "display_name": "Kingdom of Embers", "color": "#B33A2E" }
```
The file's own location (`<namespace>:<id>`) is the faction's id. Any number of datapacks can each
contribute factions. Registering a faction here is what makes it eligible to actually receive
Voronoi territory - a `structure_set` referencing a faction that was never registered this way will
simply never generate anywhere, rather than silently ignoring the restriction.

### Opting a structure set into a faction
Use the `facthan:faction_spread` placement type in a `structure_set` JSON instead of
`minecraft:random_spread`, adding one extra field:
```json
{
  "structures": [{ "structure": "minecraft:village_plains", "weight": 1.0 }],
  "placement": {
    "type": "facthan:faction_spread",
    "salt": 12345,
    "spacing": 24,
    "separation": 8,
    "faction": "<namespace>:<id>"
  }
}
```
Every other `random_spread` field (`spacing`, `separation`, `salt`, `frequency`, `locate_offset`,
`exclusion_zone`, `spread_type`) works exactly as it does today - `faction` is the only addition.
A structure set left on `minecraft:random_spread` is entirely unaffected by this mod.

## License

Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - share, modify, and
redistribute freely, including commercially, as long as you credit ThanWiggins.
