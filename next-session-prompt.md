This repo (a Forge Minecraft mod called Facthan, package com.thanwiggins.facthan) currently
implements a political-map system: the world is divided into Voronoi/Worley cells (PoliticalMap.java),
each cell is deterministically assigned to a datapack-defined faction (FactionRegistry.java,
PoliticalMapService.java), and a custom structure_set placement type
("mcaichat:faction_spread", in FactionStructurePlacement.java) vetoes vanilla's normal
random_spread structure candidates that fall outside their faction's territory. Nothing about
this system is persisted to disk — it's a pure function of the world seed, recomputed on demand.

The user wants to pivot this mod significantly — from a "political map generator" (which only
ever filters/subtracts from vanilla's existing structure placement) to a "kingdom generator"
that deliberately, reliably places a designed set of structures per faction. The full design is
written up in desired-results.md at the repo root — READ IT IN FULL before doing anything else.
Also skim the existing Java source under src/main/java/com/thanwiggins/facthan/ (8 files, small)
to understand what's already built, since the plan builds on/replaces parts of it.

At a high level, desired-results.md describes three steps: (1) config additions (capital count,
min-distance-from-origin, min-distance-between-capitals, and count/range/spacing config for
"supporting structures" around each capital — all in a standing config file, not a datapack),
(2) a capital-placement routine that randomly picks X unique factions, rolls locations meeting
all distance/validity constraints with bounded retries, and — critically — flushes the world
seed and starts over if it can't find valid capitals within 10 attempts, then force-generates
capital structures at the finalized locations with no exceptions, and (3) a realm-building
routine that scatters a random number of supporting structures in an annulus around each
capital, with its own bounded per-structure retry that just gives up on that one structure
(no world-flush) if it fails 5 times.

## Your task

Do NOT start drafting an implementation plan yet. First, review desired-results.md critically
and produce exactly 10 clarifying questions whose answers would materially change how this
gets implemented — things that are ambiguous, underspecified, or where multiple valid technical
approaches exist and the choice matters. Ask them of the user (via AskUserQuestion, batched
sensibly) before proceeding.

A prior conversation already surfaced several open issues in this same design — make sure your
10 questions cover the ones below that are still relevant after your own read of the current
doc, but don't just recite this list uncritically: re-derive from the doc, drop anything already
resolved, add anything new you find, and prioritize by implementation impact.

Known open issues from the prior conversation:
- Where does "meets ALL the criteria for generation" cut off — biome-only, or full jigsaw/terrain
  fit? The heavier check requires invoking the structure's real generation-point logic outside
  its normal per-chunk lifecycle, which is more engineering than a biome check.
- "Playable world" is bounded by an external mod (github.com/thanwiggins/worldborder) referenced
  but not vendored here — does it set vanilla's own WorldBorder (queryable directly), or maintain
  its own separate bound that this mod would need to integrate with explicitly?
- "Flush the world seed and start over" (step 2) is a much bigger operation than a retry loop —
  it means deleting/recreating save data. When exactly is this allowed to fire (must be before
  any player has ever joined the world), and what should the player actually see happen?
- This whole design requires persisting chosen-once state (capital and supporting-structure
  locations) via something like Forge SavedData — a real departure from the rest of the mod's
  "pure function of the seed, nothing persisted" design (see the comment atop PoliticalMap.java).
  Is that split intentional, and should the existing Voronoi/political-map mechanism be kept
  alongside this, replaced by it, or repurposed (e.g., only for factions that don't get a capital)?
- Step 2 selects X unique factions out of however many are registered — what happens to a
  registered faction that ISN'T selected to have a capital this world? No structures at all, or
  some fallback behavior?
- Step 2's failure mode (global seed-flush) and step 3's failure mode (silent per-structure give-up,
  possibly yielding fewer supporting structures than configured) are asymmetric in severity — confirm
  that's intentional.
- Step 3: does a failed reroll re-pick both the structure type AND the location, or keep the type
  fixed and only reroll location?
- Step 3: can the same non-capital structure type repeat multiple times within one realm, or must
  all supporting structures in a realm be distinct types?
- Where does the "this structure is the Capital for this faction" designation actually live in
  the datapack schema — on the Faction JSON, or on the structure_set's placement JSON?
- What happens if the configured capital count (X) exceeds the number of factions actually
  registered via datapacks?

## After the questions are answered

Once the user has answered, use their answers plus the doc to draft a concrete implementation
plan (use plan mode) covering: config schema (new ForgeConfigSpec fields, extending or replacing
PoliticalConfig.java), datapack schema changes (Faction/structure_set JSON), the new placement
mechanism(s) needed to force-generate at pre-decided locations, where and how the capital/realm
planning logic hooks into the world's lifecycle, the persistence layer, and what happens to the
existing Voronoi political-map code (PoliticalMap.java, PoliticalMapService.java,
FactionStructurePlacement.java) — keep it, repurpose it, or retire it. Get the user's sign-off on
the plan before writing any code.
