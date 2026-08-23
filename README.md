# Facthan

A Minecraft Forge mod that turns structure generation into a deterministic "kingdom generator."
Once per world, before any chunk ever generates, Facthan picks a set of factions, force-generates
each one's **capital** structure at a location that satisfies your distance rules and actually
passes real structure generation (not just a biome check) - no exceptions - then scatters a random
set of that faction's other ("supporting") structures around the capital in a realm. Everything
else (factions that weren't picked this world, and any structure with no faction at all) generates
exactly as vanilla `random_spread` always has.

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
- **Unselected factions are unrestricted** - a registered faction that isn't chosen as a capital
  this world places its structures exactly like plain `minecraft:random_spread`, no territory
  concept at all.
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

- `capitalCount` (default `3`) - how many factions get a capital this world. Clamped down to
  however many factions actually have an `is_capital` structure_set registered, if fewer.
- `minDistanceFromOrigin` (default `250`) - minimum distance, in blocks, a capital may be from
  the world origin.
- `minDistanceBetweenCapitals` (default `500`) - minimum distance, in blocks, a capital may be
  from every other capital.
- `minSupportingStructures` / `maxSupportingStructures` (default `3` / `5`) - how many additional
  structures are placed in each realm.
- `minSupportingStructureRange` / `maxSupportingStructureRange` (default `100` / `200`) - how far
  a supporting structure may be from its capital.
- `minSupportingStructureSeparation` (default `50`) - minimum distance a supporting structure must
  be from every other supporting structure already placed in the same realm.
- `fallbackWorldBorderRadius` (default `1000`) - used only when Than's Worldborder isn't installed
  or its custom overworld border is disabled.

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
the capital/realm search - normal generation is vetoed for all of them, no exceptions. If it's
*not* selected, they're all completely unrestricted, exactly like plain `random_spread`.

`spacing`, `separation`, `salt`, `frequency`, `locate_offset`, `exclusion_zone`, and `spread_type`
are still inherited from `random_spread` and still matter for a faction that *isn't* selected as a
capital this world.

## Mod Interactions

**Than's Worldborder** ([github.com/thanwiggins/worldborder](https://github.com/thanwiggins/worldborder)) -
optional. When installed with its custom overworld border enabled, the capital search stays inside
that bound instead of `fallbackWorldBorderRadius`.

## License

Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - share, modify, and
redistribute freely, including commercially, as long as you credit ThanWiggins.
