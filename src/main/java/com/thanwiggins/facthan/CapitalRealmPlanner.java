package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The heart of the "kingdom generator" pivot (see desired-results.md): once per world, before any
// chunk generates, deterministically picks a set of capital factions, force-generates each one's
// capital structure at a validated location, then scatters each capital's supporting structures
// around it - guaranteed, not deferred (see FactionStructurePlacement for why the "let vanilla's own
// pipeline pick it up later" alternative was dropped: it depends on a separately-loaded mod's own
// structure_set never independently competing for the same structure, which isn't something this
// mod can control or verify).
//
// Everything is *validated* first, with zero side effects (see validateOnly/validateBatch), for the
// entire batch - capitals AND every realm's supporting structures - before a single block gets
// written. Only once a full batch validates does forceGenerateBatch actually write it all. This
// fixes two real bugs the original version of this class had:
//   1. Force-generation only ever called placeInChunk once, for the origin chunk, with a small
//      hardcoded +/-6 block box - so any structure bigger than one chunk (any castle) only ever got
//      its origin chunk's slice written; the rest silently generated as plain terrain. Verified
//      against vanilla's actual "/place structure" command (decompiled): it loops placeInChunk once
//      per chunk in the structure's real bounding box, each time with that chunk's own full-height
//      bounding box - now done identically here.
//   2. The per-faction placement veto (see FactionStructurePlacement) only ever activated at the very
//      end, after every capital and every realm structure had already been force-generated - so while
//      a faction's OWN capital was still being written, its other, not-yet-vetoed structure_sets could
//      still independently fire via their ordinary spacing grid, forced into existence by this same
//      routine's own chunk-loading, producing extra/duplicated copies. Now the veto is activated (via
//      KingdomSavedData.reserveFactions) the moment the full capital set is validated, strictly before
//      any force-generation begins.
// A side effect of validating everything before writing anything: a failed attempt (a later capital
// or realm structure that can't find a spot) never leaves earlier, already-force-generated structures
// from that same failed attempt orphaned in the world - there's nothing to clean up, because nothing
// was written until the whole batch succeeded.
//
// Invoked from MinecraftServerMixin's injection into MinecraftServer#prepareLevels - see that class
// for exactly why that hook point (and not a Forge lifecycle event) is the right one.
public final class CapitalRealmPlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CAPITAL_SEARCH_ATTEMPTS = 10;
    private static final int MAX_CAPITAL_LOCATION_ROLLS = 64;
    private static final int MAX_SUPPORTING_STRUCTURE_RETRIES = 5;
    private static final int MAX_ORPHAN_STRUCTURE_RETRIES = 5;
    // Distinct from the attempt-index salts (1..MAX_CAPITAL_SEARCH_ATTEMPTS) and the per-faction
    // realm/orphan salts (faction.toString().hashCode()) used elsewhere in this class, so the
    // capital-count roll below never shares an RNG stream with either.
    private static final long CAPITAL_COUNT_SALT = 0x1DE5170CL;

    private record Placement(BlockPos pos, Structure structure) {}

    private CapitalRealmPlanner() {}

    public static void planAndForceGenerate(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        KingdomSavedData data = KingdomSavedData.get(overworld);
        if (data.isFinalized()) return;

        // ChunkStatus.STRUCTURE_STARTS's own generation task is gated on this - but we don't go
        // through that task at all, we call Structure.generate()/placeInChunk() directly, so without
        // this check we'd force-generate capitals into a world that globally can never have any
        // OTHER structure either, which would be a very confusing, inconsistent result.
        if (!server.getWorldData().worldGenOptions().generateStructures()) {
            LOGGER.error("This world was created with \"Generate Structures\" disabled - Facthan cannot " +
                    "place any capitals or realms. Recreate the world with that option enabled if you want " +
                    "kingdom generation to do anything.");
            data.markFinalized();
            return;
        }

        List<ResourceLocation> registeredFactions = FactionRegistry.orderedFactionIds();
        if (registeredFactions.isEmpty()) {
            LOGGER.info("No political factions registered - skipping kingdom generation.");
            data.markFinalized();
            return;
        }

        int minCapitalCount = KingdomConfig.MIN_CAPITAL_COUNT.get();
        int maxCapitalCount = KingdomConfig.MAX_CAPITAL_COUNT.get();
        if (minCapitalCount > maxCapitalCount) {
            LOGGER.warn("minCapitalCount ({}) is greater than maxCapitalCount ({}) - clamping minCapitalCount down to it.",
                    minCapitalCount, maxCapitalCount);
            minCapitalCount = maxCapitalCount;
        }
        RandomSource capitalCountRandom = RandomSource.create(mixSeed(overworld.getSeed(), CAPITAL_COUNT_SALT));
        int configuredCapitalCount = capitalCountRandom.nextIntBetweenInclusive(minCapitalCount, maxCapitalCount);
        int capitalCount = Math.min(configuredCapitalCount, registeredFactions.size());
        if (capitalCount < configuredCapitalCount) {
            LOGGER.warn("Rolled capital count ({}) exceeds the number of registered factions ({}) - clamping.",
                    configuredCapitalCount, registeredFactions.size());
        }
        if (capitalCount <= 0) {
            data.markFinalized();
            return;
        }

        FactionStructures structures = FactionStructures.gather(server.registryAccess());
        WorldBorderCompat.Bounds bounds = WorldBorderCompat.overworldBounds();

        Map<ResourceLocation, Placement> capitals = null;
        for (int attempt = 1; attempt <= MAX_CAPITAL_SEARCH_ATTEMPTS; attempt++) {
            KingdomBootStatus.set("Searching for capital locations...");
            RandomSource random = RandomSource.create(mixSeed(overworld.getSeed(), attempt));
            capitals = tryValidateCapitals(server, overworld, structures, registeredFactions, capitalCount, bounds, random);
            if (capitals != null) break;
        }

        if (capitals == null) {
            String message = "Facthan couldn't find valid capital locations after " + MAX_CAPITAL_SEARCH_ATTEMPTS +
                    " attempts on this world seed. This world will not be created - please create it again to try a new seed.";
            LOGGER.error(message);
            throw new KingdomGenerationAbortedException(message);
        }

        // Every registered faction ends up either a capital-faction (this world) or an orphan
        // faction (below) - both get every one of their structures force-generated by this routine,
        // so natural generation should never be allowed to fire for ANY registered faction's
        // structure_set, ever. Locked in NOW, strictly before any force-generation - otherwise our
        // own forced chunk-loading below could still trigger a not-yet-vetoed structure_set via its
        // ordinary spacing grid. This is a backstop alongside disabling these structures in
        // Structurify (or similar), not the only line of defense against natural duplicates.
        KingdomSavedData.reserveFactions(Set.copyOf(registeredFactions));

        // Progress text below is deliberately faction-agnostic (a count, never a faction id or a
        // number) - this runs on the "Building world" screen before the player has spawned in, and
        // naming which faction just got a capital, or letting the player count how many times a step
        // repeats, would spoil exactly what CapitalRealmPlanner exists to let the player discover by
        // exploring - including how many capitals/orphan factions this world rolled.
        Map<ResourceLocation, List<Placement>> realms = new LinkedHashMap<>();

        // Shared across every remaining phase (realms, then orphans) - supporting structures only
        // (no capitals), what minSupportingStructureRange/minOrphanStructureDistanceFromSupportingStructures
        // actually mean by "supporting structure". Appended to as each phase places its own
        // structures, so later phases see everything earlier ones already claimed.
        List<BlockPos> allSupporting = new ArrayList<>();

        for (Map.Entry<ResourceLocation, Placement> entry : capitals.entrySet()) {
            ResourceLocation faction = entry.getKey();
            KingdomBootStatus.set("Searching for realm structures...");
            RandomSource realmRandom = RandomSource.create(mixSeed(overworld.getSeed(), faction.toString().hashCode()));
            List<Placement> realm = validateRealm(server, overworld, structures, faction, entry.getValue().pos(),
                    bounds, realmRandom, allSupporting);
            realms.put(faction, realm);
        }

        List<ResourceLocation> orphanFactions = new ArrayList<>(registeredFactions);
        orphanFactions.removeAll(capitals.keySet());

        Map<ResourceLocation, List<Placement>> orphans = new LinkedHashMap<>();
        for (ResourceLocation faction : orphanFactions) {
            KingdomBootStatus.set("Searching for orphan structures...");
            RandomSource orphanRandom = RandomSource.create(mixSeed(overworld.getSeed(), faction.toString().hashCode()));
            orphans.put(faction, validateOrphans(server, overworld, structures, faction, bounds, orphanRandom,
                    positionsOf(capitals.values()), allSupporting));
        }

        for (Map.Entry<ResourceLocation, Placement> entry : capitals.entrySet()) {
            ResourceLocation faction = entry.getKey();
            Placement capital = entry.getValue();
            KingdomBootStatus.set("Generating capital and realm...");

            forceGenerate(overworld, capital.structure(), capital.pos());
            data.putCapital(faction, capital.pos());

            for (Placement supporting : realms.getOrDefault(faction, List.of())) {
                forceGenerate(overworld, supporting.structure(), supporting.pos());
                data.addSupportingStructure(faction, supporting.pos());
            }
        }

        for (ResourceLocation faction : orphanFactions) {
            KingdomBootStatus.set("Generating orphan structures...");

            for (Placement orphan : orphans.getOrDefault(faction, List.of())) {
                forceGenerate(overworld, orphan.structure(), orphan.pos());
                data.addSupportingStructure(faction, orphan.pos());
            }
        }

        data.markFinalized();
        KingdomBootStatus.clear();
        LOGGER.info("Kingdom generation finalized: {} capital(s) placed, {} orphan faction(s) processed.",
                capitals.size(), orphanFactions.size());
    }

    // One full attempt to validate a location for every selected capital on a single derived RNG
    // stream - returns null (never partial results) the moment any capital can't find a valid spot,
    // so the caller knows to retry the whole batch with a fresh attempt seed. Pure validation, no
    // side effects - nothing gets written to the world here.
    private static Map<ResourceLocation, Placement> tryValidateCapitals(MinecraftServer server, ServerLevel overworld,
            FactionStructures structures, List<ResourceLocation> registeredFactions, int capitalCount,
            WorldBorderCompat.Bounds bounds, RandomSource random) {
        List<ResourceLocation> eligible = new ArrayList<>();
        for (ResourceLocation faction : registeredFactions) {
            if (structures.capitalStructures().containsKey(faction)) eligible.add(faction);
        }
        if (eligible.size() < capitalCount) {
            LOGGER.warn("Only {} faction(s) have an is_capital structure_set registered, but capitalCount is {} - clamping.",
                    eligible.size(), capitalCount);
            capitalCount = eligible.size();
        }
        if (capitalCount <= 0) return Map.of();

        shuffle(eligible, random);
        List<ResourceLocation> selected = eligible.subList(0, capitalCount);

        Map<ResourceLocation, Placement> placed = new LinkedHashMap<>();
        int minDistanceFromOrigin = KingdomConfig.MIN_DISTANCE_FROM_ORIGIN.get();
        int minDistanceBetweenCapitals = KingdomConfig.MIN_DISTANCE_BETWEEN_CAPITALS.get();

        for (ResourceLocation faction : selected) {
            Structure capitalStructure = structures.pickCapitalStructure(faction, random);
            boolean found = false;

            for (int locationAttempt = 0; locationAttempt < MAX_CAPITAL_LOCATION_ROLLS && !found; locationAttempt++) {
                int x = random.nextIntBetweenInclusive(bounds.minX(), bounds.maxX());
                int z = random.nextIntBetweenInclusive(bounds.minZ(), bounds.maxZ());

                if (distance(x, z, 0, 0) < minDistanceFromOrigin) continue;
                if (tooCloseToAny(positionsOf(placed.values()), x, z, minDistanceBetweenCapitals)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (validateOnly(server, overworld, capitalStructure, target)) {
                    placed.put(faction, new Placement(target, capitalStructure));
                    found = true;
                }
            }

            if (!found) return null;
        }

        return placed;
    }

    // Same idea as tryValidateCapitals, for one capital's realm - pure validation, no side effects.
    // A slot that never finds a spot within its retry budget is silently dropped (no world flush).
    // allSupporting is shared across every realm (and later, every orphan faction) - see
    // planAndForceGenerate - so this realm's own placements become visible to whichever realm or
    // orphan faction is validated next, for their own "distance from supporting structures" checks.
    private static List<Placement> validateRealm(MinecraftServer server, ServerLevel overworld,
            FactionStructures structures, ResourceLocation faction, BlockPos capitalPos,
            WorldBorderCompat.Bounds bounds, RandomSource random, List<BlockPos> allSupporting) {
        List<Structure> pool = structures.supportingStructures().get(faction);
        if (pool == null || pool.isEmpty()) return List.of();

        int count = random.nextIntBetweenInclusive(
                KingdomConfig.MIN_SUPPORTING_STRUCTURES.get(), KingdomConfig.MAX_SUPPORTING_STRUCTURES.get());
        int minRange = KingdomConfig.MIN_SUPPORTING_STRUCTURE_RANGE.get();
        int maxRange = KingdomConfig.MAX_SUPPORTING_STRUCTURE_RANGE.get();
        int minSeparation = KingdomConfig.MIN_SUPPORTING_STRUCTURE_SEPARATION.get();

        List<Placement> placedThisRealm = new ArrayList<>();

        for (int slot = 0; slot < count; slot++) {
            // Repeats of the same structure type within one realm are allowed by design - keep the
            // picked type fixed across all retries for this slot, only rerolling the location.
            Structure structure = pool.get(random.nextInt(pool.size()));
            boolean placed = false;

            for (int retry = 0; retry < MAX_SUPPORTING_STRUCTURE_RETRIES && !placed; retry++) {
                double angle = random.nextDouble() * Math.PI * 2;
                int range = random.nextIntBetweenInclusive(minRange, maxRange);
                int x = capitalPos.getX() + (int) Math.round(Math.cos(angle) * range);
                int z = capitalPos.getZ() + (int) Math.round(Math.sin(angle) * range);

                if (!bounds.contains(x, z)) continue;
                if (tooCloseToAny(positionsOf(placedThisRealm), x, z, minSeparation)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (validateOnly(server, overworld, structure, target)) {
                    placedThisRealm.add(new Placement(target, structure));
                    allSupporting.add(target);
                    placed = true;
                }
            }

            if (!placed) {
                LOGGER.warn("Gave up on a supporting structure for {} after {} retries.", faction, MAX_SUPPORTING_STRUCTURE_RETRIES);
            }
        }

        return placedThisRealm;
    }

    // Same validate-first, drop-and-warn-on-failure idea as validateRealm, but for an "orphan"
    // faction's supporting structures - a faction that didn't get a capital (and so didn't get a
    // validateRealm call at all) this world. No capital position to center an angle/range roll on,
    // so candidate positions are rolled flat across the world border bounds instead, the same way
    // tryValidateCapitals rolls capital positions.
    private static List<Placement> validateOrphans(MinecraftServer server, ServerLevel overworld,
            FactionStructures structures, ResourceLocation faction, WorldBorderCompat.Bounds bounds,
            RandomSource random, List<BlockPos> capitalPositions, List<BlockPos> allSupporting) {
        List<Structure> pool = structures.supportingStructures().get(faction);
        if (pool == null || pool.isEmpty()) return List.of();

        int minDistanceFromOrigin = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_ORIGIN.get();
        int minDistanceFromCapitals = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_CAPITALS.get();
        int minDistanceFromSupporting = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_SUPPORTING_STRUCTURES.get();

        List<Placement> placedForFaction = new ArrayList<>();

        // Each unique supporting structure gets its own slot, placed at most once - "may generate in
        // the world a maximum of 1 time" per structure, matching what triggered this whole routine.
        for (Structure structure : pool) {
            boolean placed = false;

            for (int retry = 0; retry < MAX_ORPHAN_STRUCTURE_RETRIES && !placed; retry++) {
                int x = random.nextIntBetweenInclusive(bounds.minX(), bounds.maxX());
                int z = random.nextIntBetweenInclusive(bounds.minZ(), bounds.maxZ());

                if (distance(x, z, 0, 0) < minDistanceFromOrigin) continue;
                if (tooCloseToAny(capitalPositions, x, z, minDistanceFromCapitals)) continue;
                if (tooCloseToAny(allSupporting, x, z, minDistanceFromSupporting)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (validateOnly(server, overworld, structure, target)) {
                    placedForFaction.add(new Placement(target, structure));
                    allSupporting.add(target);
                    placed = true;
                }
            }

            if (!placed) {
                LOGGER.warn("Gave up on an orphan structure for {} after {} retries.", faction, MAX_ORPHAN_STRUCTURE_RETRIES);
            }
        }

        return placedForFaction;
    }

    private static boolean tooCloseToAny(Iterable<BlockPos> positions, int x, int z, int minDistance) {
        for (BlockPos other : positions) {
            if (distance(x, z, other.getX(), other.getZ()) < minDistance) return true;
        }
        return false;
    }

    private static List<BlockPos> positionsOf(Iterable<Placement> placements) {
        List<BlockPos> positions = new ArrayList<>();
        for (Placement p : placements) positions.add(p.pos());
        return positions;
    }

    // Validation only - never places a single block or forces a chunk to load. Matches
    // ChunkGenerator#tryGenerateStructure's own predicate (structure.biomes()::contains) rather than
    // /place structure's deliberate "always valid" bypass, per this design's "meets ALL the criteria
    // for generation" requirement (see desired-results.md) - a capital should only ever land somewhere
    // that would have generated there naturally, we're just not leaving it to chance.
    private static boolean validateOnly(MinecraftServer server, ServerLevel overworld, Structure structure, BlockPos target) {
        ServerChunkCache chunkSource = overworld.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        StructureTemplateManager templateManager = server.getStructureManager();
        ChunkPos chunkPos = new ChunkPos(target);

        StructureStart start = structure.generate(
                server.registryAccess(), generator, generator.getBiomeSource(), randomState,
                templateManager, overworld.getSeed(), chunkPos, 0, overworld, structure.biomes()::contains
        );

        return start.isValid();
    }

    // The actual force-generation, run only after the whole batch (this capital and every one of its
    // realm's supporting structures) has already validated. Drives Structure.generate() directly (the
    // same code path vanilla's own "/place structure" command uses) and then places it - but, unlike
    // the buggy original version of this method, loops placeInChunk once per chunk the structure's
    // real bounding box touches, each time with THAT chunk's own full-height bounding box, exactly
    // matching /place structure's decompiled behavior. A single placeInChunk call for only the origin
    // chunk (what this used to do) is why a structure bigger than one chunk - any castle - only ever
    // got a fragment of itself written.
    private static void forceGenerate(ServerLevel overworld, Structure structure, BlockPos target) {
        ServerChunkCache chunkSource = overworld.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        ChunkPos originChunk = new ChunkPos(target);

        // Re-generate (not re-validate) at real-generation time - same deterministic inputs as
        // validateOnly, so this is guaranteed to succeed given validateOnly already did.
        StructureStart start = structure.generate(
                overworld.registryAccess(), generator, generator.getBiomeSource(), randomState,
                overworld.getServer().getStructureManager(), overworld.getSeed(), originChunk, 0, overworld,
                structure.biomes()::contains
        );

        if (!start.isValid()) {
            LOGGER.error("A location for {} validated during the search but failed to regenerate identically " +
                    "at force-generation time at {} - this should not be possible; please report this.", structure, target);
            return;
        }

        BoundingBox box = start.getBoundingBox();
        ChunkPos minChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos maxChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));

        List<ChunkPos> touchedChunks = new ArrayList<>();
        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(touchedChunks::add);

        // Pass 1: force every touched chunk all the way to FULL generation status BEFORE registering
        // or placing anything. Forcing an ungenerated chunk to load runs it through EVERY generation
        // stage, including "structure references" (which scans nearby chunks for an ALREADY
        // REGISTERED structure start) and "features" (which, if a reference was found, calls
        // placeInChunk on its own). If we registered our start while other touched chunks hadn't
        // finished generating yet, each of THEIR OWN normal generation passes would discover our
        // just-registered start and place this structure into themselves automatically - on top of
        // the explicit placement in pass 2 below - two placements at the same coordinates, which is
        // exactly why entities were being duplicated (blocks silently no-op on a second identical
        // write; entities don't). Real vanilla "/place structure" never hits this because it requires
        // every touched chunk to already be fully loaded first (it refuses to run otherwise) - by
        // then each chunk's own generation has long since finished with nothing registered to react
        // to. We can't require that (we're running before any chunk exists at all), so we reproduce
        // the same precondition ourselves: fully generate first, register/place second.
        Map<ChunkPos, ChunkAccess> chunksByPos = new LinkedHashMap<>();
        for (ChunkPos chunkPos : touchedChunks) {
            chunksByPos.put(chunkPos, overworld.getChunk(chunkPos.x, chunkPos.z));
        }

        // Pass 2: every touched chunk is now FULL with nothing registered for any of them - safe to
        // register and place without anything else reacting to it mid-loop.
        StructureManager structureManager = overworld.structureManager();
        for (ChunkPos chunkPos : touchedChunks) {
            ChunkAccess chunk = chunksByPos.get(chunkPos);
            structureManager.setStartForStructure(SectionPos.of(chunkPos, 0), structure, start, chunk);
            start.placeInChunk(overworld, structureManager, generator, overworld.getRandom(),
                    new BoundingBox(chunkPos.getMinBlockX(), overworld.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX(), overworld.getMaxBuildHeight(), chunkPos.getMaxBlockZ()),
                    chunkPos);
        }
    }

    private static double distance(int x1, int z1, int x2, int z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void shuffle(List<ResourceLocation> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            ResourceLocation tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private static long mixSeed(long seed, long salt) {
        long h = seed ^ Long.rotateLeft(salt, 17);
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    // Gathers, once per attempt-batch, which structure_set is each faction's capital and which
    // structure_sets are its non-capital ("supporting") structures - derived purely from which
    // structure_sets in the registry use "facthan:faction_spread" with a matching "faction" field,
    // per FactionStructurePlacement. A structure_set's own weighted entries are respected as-is.
    private record FactionStructures(Map<ResourceLocation, List<Structure>> capitalStructures,
                                      Map<ResourceLocation, List<Structure>> supportingStructures) {

        static FactionStructures gather(RegistryAccess registryAccess) {
            Map<ResourceLocation, List<Structure>> capitals = new HashMap<>();
            Map<ResourceLocation, List<Structure>> supporting = new HashMap<>();

            registryAccess.registryOrThrow(Registries.STRUCTURE_SET).entrySet().forEach(entry -> {
                StructureSet set = entry.getValue();
                StructurePlacement placement = set.placement();
                if (!(placement instanceof FactionStructurePlacement fsp)) return;

                Map<ResourceLocation, List<Structure>> target = fsp.isCapital() ? capitals : supporting;
                List<Structure> list = target.computeIfAbsent(fsp.faction(), f -> new ArrayList<>());
                set.structures().forEach(selection -> list.add(selection.structure().value()));
            });

            return new FactionStructures(capitals, supporting);
        }

        Structure pickCapitalStructure(ResourceLocation faction, RandomSource random) {
            List<Structure> candidates = capitalStructures.get(faction);
            return candidates.get(random.nextInt(candidates.size()));
        }
    }
}
