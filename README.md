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
4. Define your factions' structures via a datapack, and assign them to factions via
   `config/facthan-common.toml` (see Configuration below).
5. Create a new world. The capital search runs automatically on the "Building world" loading
   screen, before any spawn chunk generates - nothing else to trigger.

The mod does nothing to a world unless `structureAssignments` has at least one `capital` entry.

## Configuration

### `config/facthan-common.toml`
A COMMON-type config, not CLIENT - the capital/realm search only ever runs on whichever process is
actually generating the world (the dedicated server, or the integrated server in singleplayer), so
only that process's own copy of these settings can ever matter.

- `capitalCount` (default `3`) - how many factions get a capital this world. Clamped down to
  however many factions actually have a `capital` entry in `structureAssignments`, if fewer.
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
- `flushXaeroMapCache` (default `true`) - deletes any Xaero's Minimap/World Map cache folder found
  in a brand new world's save directory right after capitals/realms are force-generated, so a
  force-generated capital doesn't show up on the map before it's ever been explored.
- `structureAssignments` (default empty) - which faction each `facthan:faction_spread` structure_set
  belongs to, and whether it's that faction's capital or one of its supporting structures. See below.

### Designating a faction's structures
Use the `facthan:faction_spread` placement type in a `structure_set` JSON instead of
`minecraft:random_spread` - its fields are otherwise identical to `random_spread`'s own:
```json
{
  "structures": [{ "structure": "<namespace>:throne_hall", "weight": 1.0 }],
  "placement": {
    "type": "facthan:faction_spread",
    "salt": 12345,
    "spacing": 1,
    "separation": 0
  }
}
```
Then, in `config/facthan-common.toml`'s `structureAssignments` list, add one entry per structure_set
declaring which faction it belongs to and whether it's that faction's capital:
```toml
structureAssignments = [
    "<namespace>:<faction_id> capital <namespace>:<capital_structure_set_id>",
    "<namespace>:<faction_id> supporting <namespace>:<other_structure_set_id>"
]
```
A faction's own identity is whatever id you use here - there's no separate faction-registration file
anymore. Give each faction at most one `capital` entry and as many `supporting` entries as you like
(its supporting structures - repeats across a single realm are allowed). If this faction is selected
as a capital-faction this world, every one of these structure_sets is placed *only* by the
capital/realm search - normal generation is vetoed for all of them, no exceptions. If it's *not*
selected, they're all completely unrestricted, exactly like plain `random_spread`. A faction with no
`capital` entry is registered but can never be selected as a capital faction.

`spacing`, `separation`, `salt`, `frequency`, `locate_offset`, `exclusion_zone`, and `spread_type`
are still inherited from `random_spread`, still live on the structure_set's own JSON (not in config),
and still matter for a faction that *isn't* selected as a capital this world.

## Mod Interactions

**Than's Worldborder** ([github.com/thanwiggins/worldborder](https://github.com/thanwiggins/worldborder)) -
optional. When installed with its custom overworld border enabled, the capital search stays inside
that bound instead of `fallbackWorldBorderRadius`.

## License

Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - share, modify, and
redistribute freely, including commercially, as long as you credit ThanWiggins.
