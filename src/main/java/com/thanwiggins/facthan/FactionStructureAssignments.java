package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Replaces the old datapack-driven Faction/FactionRegistry - which faction a "facthan:faction_spread"
// structure_set belongs to, and whether it's that faction's capital or one of its supporting
// structures, is now declared directly in config (KingdomConfig#STRUCTURE_ASSIGNMENTS) instead of in
// data/<namespace>/political_factions/*.json plus "faction"/"is_capital" fields on the structure_set's
// own placement. The structure_set's datapack JSON itself is unavoidable (vanilla's structure_set
// registry is inherently data-driven) and still needs "type": "facthan:faction_spread" to opt into
// FactionStructurePlacement's veto, but no faction-specific fields belong there anymore.
public final class FactionStructureAssignments {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Assignment(ResourceLocation faction, boolean isCapital) {}

    // Keyed by placement object identity (relying on StructurePlacement never overriding
    // equals/hashCode, i.e. plain Object identity) - isPlacementChunk is never told its own
    // structure_set's id by vanilla, so the only way it can ever answer "which faction is MY OWN
    // structure_set assigned to" is by looking itself up here. refresh() below is the one place that
    // ever has both a structure_set's id and its placement object in hand at the same time.
    private static volatile Map<StructurePlacement, Assignment> placementLookup = Map.of();

    private FactionStructureAssignments() {}

    public static Optional<Assignment> assignmentFor(StructurePlacement placement) {
        return Optional.ofNullable(placementLookup.get(placement));
    }

    // Called once per server start (see MinecraftServerMixin), unconditionally - independent of
    // whether this world's kingdom generation has already been finalized, since this only rebuilds an
    // in-memory, per-launch lookup that has to be ready before any chunk ever generates.
    public static void refresh(RegistryAccess registryAccess) {
        Map<ResourceLocation, Assignment> byStructureSetId = parseConfig();
        Map<StructurePlacement, Assignment> lookup = new HashMap<>();
        Set<ResourceLocation> unmatched = new HashSet<>(byStructureSetId.keySet());

        registryAccess.registryOrThrow(Registries.STRUCTURE_SET).entrySet().forEach(entry -> {
            ResourceLocation id = entry.getKey().location();
            StructureSet set = entry.getValue();
            Assignment assignment = byStructureSetId.get(id);
            boolean isFactionSpread = set.placement() instanceof FactionStructurePlacement;

            if (assignment != null) {
                unmatched.remove(id);
                if (isFactionSpread) {
                    lookup.put(set.placement(), assignment);
                } else {
                    LOGGER.warn("Structure set {} is assigned to a faction in facthan-common.toml but doesn't use " +
                            "\"facthan:faction_spread\" as its placement type - it will be ignored by Facthan's " +
                            "kingdom generation entirely.", id);
                }
            } else if (isFactionSpread) {
                LOGGER.warn("Structure set {} uses \"facthan:faction_spread\" but has no matching entry in " +
                        "facthan-common.toml's structureAssignments - it will never be treated as any faction's " +
                        "capital or supporting structure.", id);
            }
        });

        unmatched.forEach(id -> LOGGER.warn("facthan-common.toml assigns a faction to structure set {}, but no such " +
                "structure set is registered - check for a typo, or a missing/disabled datapack.", id));

        placementLookup = Map.copyOf(lookup);
    }

    public static List<ResourceLocation> orderedFactionIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Assignment assignment : parseConfig().values()) {
            ids.add(assignment.faction());
        }
        return ids.stream().distinct().sorted().toList();
    }

    public static Map<ResourceLocation, Assignment> byStructureSetId() {
        return parseConfig();
    }

    private static Map<ResourceLocation, Assignment> parseConfig() {
        Map<ResourceLocation, Assignment> result = new LinkedHashMap<>();
        for (String entry : KingdomConfig.STRUCTURE_ASSIGNMENTS.get()) {
            parseEntry(entry).ifPresent(parsed -> result.put(parsed.structureSetId(), parsed.assignment()));
        }
        return result;
    }

    private record ParsedEntry(ResourceLocation structureSetId, Assignment assignment) {}

    private static Optional<ParsedEntry> parseEntry(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 3) {
            LOGGER.error("Skipping invalid structureAssignments entry \"{}\" - expected \"<faction> capital|supporting <structure_set>\".", raw);
            return Optional.empty();
        }

        boolean isCapital;
        if (parts[1].equalsIgnoreCase("capital")) {
            isCapital = true;
        } else if (parts[1].equalsIgnoreCase("supporting")) {
            isCapital = false;
        } else {
            LOGGER.error("Skipping invalid structureAssignments entry \"{}\" - the second word must be \"capital\" or \"supporting\".", raw);
            return Optional.empty();
        }

        ResourceLocation faction = ResourceLocation.tryParse(parts[0]);
        ResourceLocation structureSet = ResourceLocation.tryParse(parts[2]);
        if (faction == null || structureSet == null) {
            LOGGER.error("Skipping invalid structureAssignments entry \"{}\" - faction and structure set must both be valid resource locations.", raw);
            return Optional.empty();
        }

        return Optional.of(new ParsedEntry(structureSet, new Assignment(faction, isCapital)));
    }
}
