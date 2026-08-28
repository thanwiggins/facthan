package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
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
    // A faction listed in orphanPriorityFactions gets this many retries instead - meaningfully more
    // persistent, so it's actually more likely to find a spot, rather than just being tried first
    // with the same odds as everyone else.
    private static final int PRIORITY_ORPHAN_STRUCTURE_RETRIES = 50;
    // Distinct from the attempt-index salts (1..MAX_CAPITAL_SEARCH_ATTEMPTS) and the per-faction
    // realm/orphan salts (faction.toString().hashCode()) used elsewhere in this class, so the
    // capital-count roll below never shares an RNG stream with either.
    private static final long CAPITAL_COUNT_SALT = 0x1DE5170CL;

    private record Placement(BlockPos pos, Structure structure) {}

    // What forceGenerate actually produced - the real bounding box (heightmap-projected, not the
    // Y=0 search target) and the real rotation the jigsaw system rolled for it, needed to compute
    // this placement's FrontAnchor. Null (see forceGenerate) if generation somehow failed after
    // already validating - see the error logged there.
    private record GeneratedPlacement(BoundingBox boundingBox, Rotation rotation) {}

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

        List<ResourceLocation> priorityOrphanFactions = parseOrphanPriorityFactions();

        List<ResourceLocation> orphanFactions = new ArrayList<>(registeredFactions);
        orphanFactions.removeAll(capitals.keySet());
        prioritizeOrphanFactions(orphanFactions, priorityOrphanFactions);

        Map<ResourceLocation, List<Placement>> orphans = new LinkedHashMap<>();
        for (ResourceLocation faction : orphanFactions) {
            KingdomBootStatus.set("Searching for orphan structures...");
            RandomSource orphanRandom = RandomSource.create(mixSeed(overworld.getSeed(), faction.toString().hashCode()));
            orphans.put(faction, validateOrphans(server, overworld, structures, faction, bounds, orphanRandom,
                    positionsOf(capitals.values()), allSupporting, priorityOrphanFactions));
        }

        int roadAnchorOffset = KingdomConfig.ROAD_ANCHOR_OFFSET.get();

        for (Map.Entry<ResourceLocation, Placement> entry : capitals.entrySet()) {
            ResourceLocation faction = entry.getKey();
            Placement capital = entry.getValue();
            KingdomBootStatus.set("Generating capital and realm...");

            GeneratedPlacement capitalGenerated = forceGenerate(overworld, capital.structure(), capital.pos());
            data.putCapital(faction, capital.pos());

            // Collected purely for this realm's own road-building below - never persisted, since
            // roads are built (or given up on) synchronously in this same pass, before
            // markFinalized(), so there is nothing left to redo on a later boot.
            List<BoundingBox> realmBoxes = new ArrayList<>();
            List<FrontAnchor> supportingAnchors = new ArrayList<>();
            FrontAnchor capitalAnchor = null;
            if (capitalGenerated != null) {
                realmBoxes.add(capitalGenerated.boundingBox());
                capitalAnchor = FrontAnchor.compute(structureId(overworld, capital.structure()),
                        capitalGenerated.boundingBox(), capitalGenerated.rotation(), roadAnchorOffset);
            }

            for (Placement supporting : realms.getOrDefault(faction, List.of())) {
                GeneratedPlacement generated = forceGenerate(overworld, supporting.structure(), supporting.pos());
                data.addSupportingStructure(faction, supporting.pos());
                if (generated == null) continue;

                realmBoxes.add(generated.boundingBox());
                FrontAnchor anchor = FrontAnchor.compute(structureId(overworld, supporting.structure()),
                        generated.boundingBox(), generated.rotation(), roadAnchorOffset);
                if (anchor != null) supportingAnchors.add(anchor);
            }

            if (KingdomConfig.ENABLE_ROADS.get() && capitalAnchor != null && !supportingAnchors.isEmpty()) {
                KingdomBootStatus.set("Building roads...");
                RoadBuilder.connectRealm(overworld, faction, capitalAnchor, supportingAnchors, realmBoxes);
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
                if (validateOnly(server, overworld, capitalStructure, target, bounds)) {
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
                if (validateOnly(server, overworld, structure, target, bounds)) {
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
            RandomSource random, List<BlockPos> capitalPositions, List<BlockPos> allSupporting,
            List<ResourceLocation> priorityFactionIds) {
        List<Structure> pool = structures.supportingStructures().get(faction);
        if (pool == null || pool.isEmpty()) return List.of();

        int minDistanceFromOrigin = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_ORIGIN.get();
        int minDistanceFromCapitals = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_CAPITALS.get();
        int minDistanceFromSupporting = KingdomConfig.MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_SUPPORTING_STRUCTURES.get();
        // Not just tried first (see prioritizeOrphanFactions) - also meaningfully more persistent,
        // so a priority faction is actually more likely to find a spot at all, not just first in
        // line with the same odds as everyone else.
        int maxRetries = priorityFactionIds.contains(faction) ? PRIORITY_ORPHAN_STRUCTURE_RETRIES : MAX_ORPHAN_STRUCTURE_RETRIES;

        List<Placement> placedForFaction = new ArrayList<>();

        // Each unique supporting structure gets its own slot, placed at most once - "may generate in
        // the world a maximum of 1 time" per structure, matching what triggered this whole routine.
        for (Structure structure : pool) {
            boolean placed = false;

            for (int retry = 0; retry < maxRetries && !placed; retry++) {
                int x = random.nextIntBetweenInclusive(bounds.minX(), bounds.maxX());
                int z = random.nextIntBetweenInclusive(bounds.minZ(), bounds.maxZ());

                if (distance(x, z, 0, 0) < minDistanceFromOrigin) continue;
                if (tooCloseToAny(capitalPositions, x, z, minDistanceFromCapitals)) continue;
                if (tooCloseToAny(allSupporting, x, z, minDistanceFromSupporting)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (validateOnly(server, overworld, structure, target, bounds)) {
                    placedForFaction.add(new Placement(target, structure));
                    allSupporting.add(target);
                    placed = true;
                }
            }

            if (!placed) {
                LOGGER.warn("Gave up on an orphan structure for {} after {} retries.", faction, maxRetries);
            }
        }

        return placedForFaction;
    }

    // orphanPriorityFactions, parsed once per planAndForceGenerate call and reused both for
    // reordering (below) and for the extended retry budget validateOrphans gives these factions -
    // invalid or unparseable entries are silently dropped, same graceful-degradation style as the
    // rest of Facthan's datapack-driven config.
    private static List<ResourceLocation> parseOrphanPriorityFactions() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (String id : KingdomConfig.ORPHAN_PRIORITY_FACTIONS.get()) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed != null) ids.add(parsed);
        }
        return ids;
    }

    // Moves every faction listed in priorityFactionIds to the front, in the order configured, ahead
    // of every other orphan faction - each orphan faction's structures have to avoid every earlier
    // orphan's already-claimed positions (see validateOrphans' shared allSupporting list), so
    // processing order is what actually decides who gets first pick when space is tight.
    private static void prioritizeOrphanFactions(List<ResourceLocation> orphanFactions, List<ResourceLocation> priorityFactionIds) {
        if (priorityFactionIds.isEmpty()) return;

        List<ResourceLocation> reordered = new ArrayList<>(orphanFactions.size());
        for (ResourceLocation id : priorityFactionIds) {
            if (orphanFactions.remove(id)) {
                reordered.add(id);
            }
        }
        reordered.addAll(orphanFactions);

        orphanFactions.clear();
        orphanFactions.addAll(reordered);
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
    //
    // The candidate (x, z) rolled by the caller is only ever the structure's ORIGIN - its real
    // bounding box (only known once generate() actually runs) can extend well past that origin in
    // any direction, so checking just the origin against the world border let structures generate
    // straddling it, part inside and part out. Checking the real box's own min/max corners instead
    // catches that regardless of how large the structure turns out to be or which way it's rotated.
    private static boolean validateOnly(MinecraftServer server, ServerLevel overworld, Structure structure,
                                          BlockPos target, WorldBorderCompat.Bounds bounds) {
        ServerChunkCache chunkSource = overworld.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        StructureTemplateManager templateManager = server.getStructureManager();
        ChunkPos chunkPos = new ChunkPos(target);

        StructureStart start = structure.generate(
                server.registryAccess(), generator, generator.getBiomeSource(), randomState,
                templateManager, overworld.getSeed(), chunkPos, 0, overworld, structure.biomes()::contains
        );

        if (!start.isValid()) return false;

        BoundingBox box = start.getBoundingBox();
        return bounds.contains(box.minX(), box.minZ()) && bounds.contains(box.maxX(), box.maxZ());
    }

    // The actual force-generation, run only after the whole batch (this capital and every one of its
    // realm's supporting structures) has already validated. Drives Structure.generate() directly (the
    // same code path vanilla's own "/place structure" command uses) and then places it - but, unlike
    // the buggy original version of this method, loops placeInChunk once per chunk the structure's
    // real bounding box touches, each time with THAT chunk's own full-height bounding box, exactly
    // matching /place structure's decompiled behavior. A single placeInChunk call for only the origin
    // chunk (what this used to do) is why a structure bigger than one chunk - any castle - only ever
    // got a fragment of itself written.
    private static GeneratedPlacement forceGenerate(ServerLevel overworld, Structure structure, BlockPos target) {
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
            return null;
        }

        BoundingBox box = start.getBoundingBox();
        ChunkPos minChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos maxChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));

        List<ChunkPos> touchedChunks = new ArrayList<>();
        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(touchedChunks::add);

        StructureManager structureManager = overworld.structureManager();

        // Pass 0: register the start onto every touched chunk while each is still at EMPTY (cheap -
        // no generation work happens at that status), before ANY of them advance further - the same
        // "all chunks see the same picture" principle pass 1 below already relies on, just moved
        // earlier. This is what lets terrain_adaptation/beard_box - which reads a chunk's structure
        // data during its own NOISE stage - actually see this structure while shaping terrain around
        // it, instead of finding nothing (see ForcedPlacementGuard's own comment for the full story).
        // Marked "pending" so StructureStartPlacementGuardMixin cancels the unconditional
        // auto-placement call vanilla's own FEATURES stage will otherwise make on this exact start
        // during pass 1 - without that guard, this would reintroduce the entity-duplication bug this
        // exact registration-ordering already caused once before (see this method's own history).
        ForcedPlacementGuard.markPending(start);
        try {
            for (ChunkPos chunkPos : touchedChunks) {
                ChunkAccess emptyChunk = chunkSource.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.EMPTY, true);
                structureManager.setStartForStructure(SectionPos.of(chunkPos, 0), structure, start, emptyChunk);
            }

            // Pass 1: force every touched chunk the rest of the way to FULL generation status. Each
            // one's own "structure references" stage now finds the start registered above and its
            // "features" stage will try to auto-place it - cancelled by the guard, since it's still
            // marked pending here.
            Map<ChunkPos, ChunkAccess> chunksByPos = new LinkedHashMap<>();
            for (ChunkPos chunkPos : touchedChunks) {
                chunksByPos.put(chunkPos, overworld.getChunk(chunkPos.x, chunkPos.z));
            }

            // Pass 2: every touched chunk is FULL and already has the start registered (pass 0) -
            // lift the guard and place it ourselves, for real, exactly once per chunk.
            ForcedPlacementGuard.allow(start);
            for (ChunkPos chunkPos : touchedChunks) {
                ChunkAccess chunk = chunksByPos.get(chunkPos);
                start.placeInChunk(overworld, structureManager, generator, overworld.getRandom(),
                        new BoundingBox(chunkPos.getMinBlockX(), overworld.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                                chunkPos.getMaxBlockX(), overworld.getMaxBuildHeight(), chunkPos.getMaxBlockZ()),
                        chunkPos);
            }
        } finally {
            // Guarantees the guard never leaves a stray entry behind even if something above throws -
            // this start is single-use (a fresh instance per forceGenerate call), so once we're done
            // with it here, nothing should ever match it again either way.
            ForcedPlacementGuard.allow(start);
        }

        applySnowIfSnowy(overworld, box);

        return new GeneratedPlacement(box, extractRotation(start));
    }

    // Vanilla's own "ice and snow" feature is what normally caps a snowy biome's terrain during
    // ordinary generation - but it runs as part of the FEATURES stage, strictly before
    // start.placeInChunk() above overwrites that same ground with the structure itself, so whatever
    // snow it laid down under/around the structure's footprint is gone by the time this method
    // returns. This reapplies it, once, on top of the now-finished structure, using the same
    // per-column rule vanilla's own snow layer placement uses: the biome at that exact column has to
    // be cold enough to snow there (getPrecipitationAt, not just the biome's nominal classification -
    // this also accounts for height/temperature falloff), and the position has to be a legal spot for
    // a snow layer (SnowLayerBlock's own canSurvive - skips water, leaves that don't want it, etc.).
    private static void applySnowIfSnowy(ServerLevel overworld, BoundingBox box) {
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                BlockPos surfacePos = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column.set(x, 0, z));
                Holder<Biome> biome = overworld.getBiome(surfacePos);
                if (biome.value().getPrecipitationAt(surfacePos) != Biome.Precipitation.SNOW) continue;
                if (!overworld.getBlockState(surfacePos).isAir()) continue;
                if (!Blocks.SNOW.defaultBlockState().canSurvive(overworld, surfacePos)) continue;

                overworld.setBlock(surfacePos, Blocks.SNOW.defaultBlockState(), 2);
            }
        }
    }

    // The real-world rotation this structure ended up with - only meaningful for the common case
    // (a jigsaw structure's starting piece is a PoolElementStructurePiece, which is what every
    // Valarian Conquest structure_set is). A structure whose Structure implementation doesn't
    // produce one of these (some other mod's custom Structure subclass) has no discoverable
    // rotation, so it's treated as unrotated - FrontAnchor.compute still works, it just can't be
    // more accurate than that for such a structure.
    private static Rotation extractRotation(StructureStart start) {
        List<StructurePiece> pieces = start.getPieces();
        if (!pieces.isEmpty() && pieces.get(0) instanceof PoolElementStructurePiece poolPiece) {
            return poolPiece.getRotation();
        }
        return Rotation.NONE;
    }

    private static ResourceLocation structureId(ServerLevel overworld, Structure structure) {
        return overworld.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure);
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
