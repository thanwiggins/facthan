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

// The heart of the "kingdom generator" pivot (see desired-results.md): once per world, before any
// chunk generates, deterministically picks a set of capital factions, force-generates each one's
// capital structure at a validated location, then scatters each capital's supporting structures
// around it. Invoked from MinecraftServerMixin's injection into MinecraftServer#prepareLevels -
// see that class for exactly why that hook point (and not a Forge lifecycle event) is the right one.
public final class CapitalRealmPlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CAPITAL_SEARCH_ATTEMPTS = 10;
    private static final int MAX_CAPITAL_LOCATION_ROLLS = 64;
    private static final int MAX_SUPPORTING_STRUCTURE_RETRIES = 5;

    private CapitalRealmPlanner() {}

    public static void planAndForceGenerate(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        KingdomSavedData data = KingdomSavedData.get(overworld);
        if (data.isFinalized()) return;

        List<ResourceLocation> registeredFactions = FactionRegistry.orderedFactionIds();
        if (registeredFactions.isEmpty()) {
            LOGGER.info("No political factions registered - skipping kingdom generation.");
            data.markFinalized();
            return;
        }

        int configuredCapitalCount = KingdomConfig.CAPITAL_COUNT.get();
        int capitalCount = Math.min(configuredCapitalCount, registeredFactions.size());
        if (capitalCount < configuredCapitalCount) {
            LOGGER.warn("capitalCount ({}) exceeds the number of registered factions ({}) - clamping.",
                    configuredCapitalCount, registeredFactions.size());
        }
        if (capitalCount <= 0) {
            data.markFinalized();
            return;
        }

        FactionStructures structures = FactionStructures.gather(server.registryAccess());
        WorldBorderCompat.Bounds bounds = WorldBorderCompat.overworldBounds();

        Map<ResourceLocation, BlockPos> capitals = null;
        for (int attempt = 1; attempt <= MAX_CAPITAL_SEARCH_ATTEMPTS; attempt++) {
            KingdomBootStatus.set("Placing capitals (attempt " + attempt + "/" + MAX_CAPITAL_SEARCH_ATTEMPTS + ")...");
            RandomSource random = RandomSource.create(mixSeed(overworld.getSeed(), attempt));
            capitals = tryPlaceCapitals(server, overworld, structures, registeredFactions, capitalCount, bounds, random);
            if (capitals != null) break;
        }

        if (capitals == null) {
            String message = "Facthan couldn't find valid capital locations after " + MAX_CAPITAL_SEARCH_ATTEMPTS +
                    " attempts on this world seed. This world will not be created - please create it again to try a new seed.";
            LOGGER.error(message);
            throw new KingdomGenerationAbortedException(message);
        }

        for (Map.Entry<ResourceLocation, BlockPos> entry : capitals.entrySet()) {
            data.putCapital(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<ResourceLocation, BlockPos> entry : capitals.entrySet()) {
            ResourceLocation faction = entry.getKey();
            BlockPos capitalPos = entry.getValue();
            KingdomBootStatus.set("Populating " + faction + "'s realm...");
            RandomSource realmRandom = RandomSource.create(mixSeed(overworld.getSeed(), faction.toString().hashCode()));
            buildRealm(server, overworld, data, structures, faction, capitalPos, realmRandom);
        }

        data.markFinalized();
        KingdomBootStatus.clear();
        LOGGER.info("Kingdom generation finalized: {} capital(s) placed.", capitals.size());
    }

    // One full attempt to place every selected capital on a single derived RNG stream - returns
    // null (never partial results) the moment any capital can't find a valid spot, so the caller
    // knows to retry the whole batch with a fresh attempt seed.
    private static Map<ResourceLocation, BlockPos> tryPlaceCapitals(MinecraftServer server, ServerLevel overworld,
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

        Map<ResourceLocation, BlockPos> placed = new LinkedHashMap<>();
        int minDistanceFromOrigin = KingdomConfig.MIN_DISTANCE_FROM_ORIGIN.get();
        int minDistanceBetweenCapitals = KingdomConfig.MIN_DISTANCE_BETWEEN_CAPITALS.get();

        for (ResourceLocation faction : selected) {
            Structure capitalStructure = structures.pickCapitalStructure(faction, random);
            boolean found = false;

            for (int locationAttempt = 0; locationAttempt < MAX_CAPITAL_LOCATION_ROLLS && !found; locationAttempt++) {
                int x = random.nextIntBetweenInclusive(bounds.minX(), bounds.maxX());
                int z = random.nextIntBetweenInclusive(bounds.minZ(), bounds.maxZ());

                if (distance(x, z, 0, 0) < minDistanceFromOrigin) continue;
                if (tooCloseToAny(x, z, placed.values(), minDistanceBetweenCapitals)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (forceGenerate(server, overworld, capitalStructure, target)) {
                    placed.put(faction, target);
                    found = true;
                }
            }

            if (!found) return null;
        }

        return placed;
    }

    private static void buildRealm(MinecraftServer server, ServerLevel overworld, KingdomSavedData data,
            FactionStructures structures, ResourceLocation faction, BlockPos capitalPos, RandomSource random) {
        List<Structure> pool = structures.supportingStructures().get(faction);
        if (pool == null || pool.isEmpty()) return;

        int count = random.nextIntBetweenInclusive(
                KingdomConfig.MIN_SUPPORTING_STRUCTURES.get(), KingdomConfig.MAX_SUPPORTING_STRUCTURES.get());
        int minRange = KingdomConfig.MIN_SUPPORTING_STRUCTURE_RANGE.get();
        int maxRange = KingdomConfig.MAX_SUPPORTING_STRUCTURE_RANGE.get();
        int minSeparation = KingdomConfig.MIN_SUPPORTING_STRUCTURE_SEPARATION.get();

        List<BlockPos> placedThisRealm = new ArrayList<>();

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

                if (tooCloseToAny(x, z, placedThisRealm, minSeparation)) continue;

                BlockPos target = new BlockPos(x, 0, z);
                if (forceGenerate(server, overworld, structure, target)) {
                    placedThisRealm.add(target);
                    data.addSupportingStructure(faction, target);
                    placed = true;
                }
            }

            if (!placed) {
                LOGGER.warn("Gave up on a supporting structure for {} after {} retries.", faction, MAX_SUPPORTING_STRUCTURE_RETRIES);
            }
        }
    }

    // The actual force-generation: drives Structure.generate directly (the same code path vanilla's
    // own "/place structure" command uses), independent of the structure_set placement pipeline. A
    // failed generation attempt (an invalid StructureStart) IS the "does this meet all the criteria"
    // check - there's no separate biome-only pre-check.
    private static boolean forceGenerate(MinecraftServer server, ServerLevel overworld, Structure structure, BlockPos target) {
        ServerChunkCache chunkSource = overworld.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        StructureTemplateManager templateManager = server.getStructureManager();
        ChunkPos chunkPos = new ChunkPos(target);

        StructureStart start = structure.generate(
                server.registryAccess(), generator, generator.getBiomeSource(), randomState,
                templateManager, overworld.getSeed(), chunkPos, 0, overworld, biome -> true
        );

        if (!start.isValid()) return false;

        BoundingBox box = start.getBoundingBox();
        ChunkPos minChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos maxChunk = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));

        StructureManager structureManager = overworld.structureManager();
        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(cp ->
                structureManager.setStartForStructure(SectionPos.of(cp, 0), structure, start, overworld.getChunk(cp.x, cp.z)));

        start.placeInChunk(overworld, structureManager, generator, overworld.getRandom(),
                new BoundingBox(target.getX() - 6, overworld.getMinBuildHeight(), target.getZ() - 6,
                        target.getX() + 6, overworld.getMaxBuildHeight(), target.getZ() + 6),
                chunkPos);

        return true;
    }

    private static boolean tooCloseToAny(int x, int z, Iterable<BlockPos> others, int minDistance) {
        for (BlockPos other : others) {
            if (distance(x, z, other.getX(), other.getZ()) < minDistance) return true;
        }
        return false;
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
    // structure_sets in the registry use "mcaichat:faction_spread" with a matching "faction" field,
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
