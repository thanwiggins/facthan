# Archived: facthan_gentle_terrain

Disabled for now (moved out of `datapacks/` so it's no longer active/distributed), kept here so it
can be restored easily if needed later - just move this folder (minus this note) back into
`datapacks/`.

What it does: overrides 3 of vanilla's own overworld terrain density functions
(`factor`/`jaggedness`/`offset` - the same 3 files vanilla's own "Amplified" world type overrides,
scaled the opposite direction: factor x1.6, jaggedness x0.4, offset x0.55) to dampen overworld
height variance, so large structures are less likely to end up half-buried in a cliff or floating
over a hole. See the git history for `README.md` around when this was added for the full writeup
and the research behind the multipliers.
