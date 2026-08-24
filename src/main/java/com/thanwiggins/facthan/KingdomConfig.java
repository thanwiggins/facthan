package com.thanwiggins.facthan;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

// Deliberately a separate COMMON-type spec, not part of Config.java's CLIENT spec - the capital
// search/realm-building routine (CapitalRealmPlanner) only ever runs on whichever process is
// actually generating the world (the dedicated server, or the integrated server in singleplayer),
// so only that process's own copy of these settings can ever matter.
public class KingdomConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue CAPITAL_COUNT;
    public static final ForgeConfigSpec.IntValue MIN_DISTANCE_FROM_ORIGIN;
    public static final ForgeConfigSpec.IntValue MIN_DISTANCE_BETWEEN_CAPITALS;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURES;
    public static final ForgeConfigSpec.IntValue MAX_SUPPORTING_STRUCTURES;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURE_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_SUPPORTING_STRUCTURE_RANGE;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURE_SEPARATION;
    public static final ForgeConfigSpec.IntValue FALLBACK_WORLD_BORDER_RADIUS;
    public static final ForgeConfigSpec.BooleanValue FLUSH_XAERO_MAP_CACHE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> STRUCTURE_ASSIGNMENTS;

    // Only checks shape ("3 whitespace-separated tokens, middle one capital/supporting") - actually
    // parsing the faction/structure_set resource locations happens in FactionStructureAssignments,
    // which can log a far more specific error than a config validator's boolean result ever could.
    private static final java.util.function.Predicate<Object> STRUCTURE_ASSIGNMENTS_VALIDATOR = element -> {
        if (!(element instanceof String s)) return false;
        String[] parts = s.trim().split("\\s+");
        return parts.length == 3 && (parts[1].equalsIgnoreCase("capital") || parts[1].equalsIgnoreCase("supporting"));
    };

    static {
        BUILDER.push("Kingdom Generation Settings");

        CAPITAL_COUNT = BUILDER.comment("How many factions get a capital (and realm) placed in a given world. " +
                        "Clamped down to however many factions are actually registered via datapacks, if fewer.")
                .defineInRange("capitalCount", 3, 0, 256);

        MIN_DISTANCE_FROM_ORIGIN = BUILDER.comment("Minimum distance, in blocks, a capital is allowed to be from the world origin (0, 0).")
                .defineInRange("minDistanceFromOrigin", 250, 0, 1_000_000);

        MIN_DISTANCE_BETWEEN_CAPITALS = BUILDER.comment("Minimum distance, in blocks, a capital is allowed to be from every other capital.")
                .defineInRange("minDistanceBetweenCapitals", 500, 0, 1_000_000);

        MIN_SUPPORTING_STRUCTURES = BUILDER.comment("Minimum number of additional (non-capital) structures placed in each faction's realm.")
                .defineInRange("minSupportingStructures", 3, 0, 4096);

        MAX_SUPPORTING_STRUCTURES = BUILDER.comment("Maximum number of additional (non-capital) structures placed in each faction's realm.")
                .defineInRange("maxSupportingStructures", 5, 0, 4096);

        MIN_SUPPORTING_STRUCTURE_RANGE = BUILDER.comment("Minimum distance, in blocks, a supporting structure is allowed to be from its capital.")
                .defineInRange("minSupportingStructureRange", 100, 0, 1_000_000);

        MAX_SUPPORTING_STRUCTURE_RANGE = BUILDER.comment("Maximum distance, in blocks, a supporting structure is allowed to be from its capital.")
                .defineInRange("maxSupportingStructureRange", 200, 0, 1_000_000);

        MIN_SUPPORTING_STRUCTURE_SEPARATION = BUILDER.comment("Minimum distance, in blocks, a supporting structure must be from every other " +
                        "supporting structure already placed in the same realm.")
                .defineInRange("minSupportingStructureSeparation", 50, 0, 1_000_000);

        FALLBACK_WORLD_BORDER_RADIUS = BUILDER.comment("Radius, in blocks, of the playable world along both axes, used only when Than's Worldborder " +
                        "mod (https://github.com/thanwiggins/worldborder) isn't installed or its custom overworld border is disabled.")
                .defineInRange("fallbackWorldBorderRadius", 1000, 1, 1_000_000);

        FLUSH_XAERO_MAP_CACHE = BUILDER.comment("Whether to delete any Xaero's Minimap/World Map cache folder found in this world's save " +
                        "directory right after capitals/realms are force-generated (only ever happens once, on a brand new world, " +
                        "before the player has spawned in). Xaero's mods cache map data straight from the world save in singleplayer, " +
                        "not just chunks the player has actually visited, which would otherwise reveal every capital/realm on the map " +
                        "immediately. Has no effect if no such folder exists yet (e.g. on a dedicated server).")
                .define("flushXaeroMapCache", true);

        STRUCTURE_ASSIGNMENTS = BUILDER.comment("Which faction each \"facthan:faction_spread\" structure_set belongs to, and whether it's that " +
                        "faction's capital or one of its supporting structures - replaces the old data/<namespace>/political_factions/*.json " +
                        "files and the \"faction\"/\"is_capital\" fields that used to live on the structure_set's own placement JSON. A faction " +
                        "with no entry here doesn't exist as far as Facthan is concerned. One entry per structure_set, each formatted as " +
                        "\"<faction id> capital|supporting <structure_set id>\", e.g.:" +
                        "\n\"valarian_conquest:valarian capital valarian_conquest:valarian_capital\"" +
                        "\n\"valarian_conquest:valarian supporting valarian_conquest:valarian_outpost\"" +
                        "\nA faction may have any number of \"supporting\" entries but should have at most one \"capital\" entry (extra ones are " +
                        "picked from arbitrarily) - a faction with no \"capital\" entry is registered but can never be selected as a capital " +
                        "faction, and just always behaves like plain random_spread.")
                .defineListAllowEmpty("structureAssignments", List.of(), STRUCTURE_ASSIGNMENTS_VALIDATOR);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
