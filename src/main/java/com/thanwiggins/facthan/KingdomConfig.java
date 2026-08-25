package com.thanwiggins.facthan;

import net.minecraftforge.common.ForgeConfigSpec;

// Deliberately a separate COMMON-type spec, not part of Config.java's CLIENT spec - the capital
// search/realm-building routine (CapitalRealmPlanner) only ever runs on whichever process is
// actually generating the world (the dedicated server, or the integrated server in singleplayer),
// so only that process's own copy of these settings can ever matter.
public class KingdomConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue MIN_CAPITAL_COUNT;
    public static final ForgeConfigSpec.IntValue MAX_CAPITAL_COUNT;
    public static final ForgeConfigSpec.IntValue MIN_DISTANCE_FROM_ORIGIN;
    public static final ForgeConfigSpec.IntValue MIN_DISTANCE_BETWEEN_CAPITALS;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURES;
    public static final ForgeConfigSpec.IntValue MAX_SUPPORTING_STRUCTURES;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURE_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_SUPPORTING_STRUCTURE_RANGE;
    public static final ForgeConfigSpec.IntValue MIN_SUPPORTING_STRUCTURE_SEPARATION;
    public static final ForgeConfigSpec.IntValue MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_ORIGIN;
    public static final ForgeConfigSpec.IntValue MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_CAPITALS;
    public static final ForgeConfigSpec.IntValue MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_SUPPORTING_STRUCTURES;
    public static final ForgeConfigSpec.IntValue FALLBACK_WORLD_BORDER_RADIUS;

    static {
        BUILDER.push("Kingdom Generation Settings");

        MIN_CAPITAL_COUNT = BUILDER.comment("Minimum number of factions that get a capital (and realm) placed in a given world - " +
                        "a random value between this and maxCapitalCount (inclusive) is rolled once per world. Clamped down to " +
                        "however many factions are actually registered via datapacks, if fewer.")
                .defineInRange("minCapitalCount", 3, 0, 256);

        MAX_CAPITAL_COUNT = BUILDER.comment("Maximum number of factions that get a capital (and realm) placed in a given world - " +
                        "see minCapitalCount. Clamped down to minCapitalCount if set lower than it.")
                .defineInRange("maxCapitalCount", 3, 0, 256);

        MIN_DISTANCE_FROM_ORIGIN = BUILDER.comment("Minimum distance, in blocks, a capital is allowed to be from the world origin (0, 0).")
                .defineInRange("minDistanceFromOrigin", 250, 0, 1_000_000);

        MIN_DISTANCE_BETWEEN_CAPITALS = BUILDER.comment("Minimum distance, in blocks, a capital is allowed to be from every other capital.")
                .defineInRange("minDistanceBetweenCapitals", 500, 0, 1_000_000);

        MIN_SUPPORTING_STRUCTURES = BUILDER.comment("Minimum number of additional (non-capital) structures placed in each faction's realm.")
                .defineInRange("minSupportingStructures", 3, 0, 4096);

        MAX_SUPPORTING_STRUCTURES = BUILDER.comment("Maximum number of additional (non-capital) structures placed in each faction's realm.")
                .defineInRange("maxSupportingStructures", 5, 0, 4096);

        MIN_SUPPORTING_STRUCTURE_RANGE = BUILDER.comment("Minimum distance, in blocks, a supporting structure is allowed to be from its capital.")
                .defineInRange("minSupportingStructureRange", 150, 0, 1_000_000);

        MAX_SUPPORTING_STRUCTURE_RANGE = BUILDER.comment("Maximum distance, in blocks, a supporting structure is allowed to be from its capital.")
                .defineInRange("maxSupportingStructureRange", 250, 0, 1_000_000);

        MIN_SUPPORTING_STRUCTURE_SEPARATION = BUILDER.comment("Minimum distance, in blocks, a supporting structure must be from every other " +
                        "supporting structure already placed in the same realm.")
                .defineInRange("minSupportingStructureSeparation", 200, 0, 1_000_000);

        MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_ORIGIN = BUILDER.comment("Minimum distance, in blocks, an orphan structure (a supporting structure " +
                        "force-generated once for a faction that didn't get a capital this world) is allowed to be from the world origin (0, 0). " +
                        "Matches minDistanceFromOrigin's default.")
                .defineInRange("minOrphanStructureDistanceFromOrigin", 250, 0, 1_000_000);

        MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_CAPITALS = BUILDER.comment("Minimum distance, in blocks, an orphan structure is allowed to be from every capital.")
                .defineInRange("minOrphanStructureDistanceFromCapitals", 500, 0, 1_000_000);

        MIN_ORPHAN_STRUCTURE_DISTANCE_FROM_SUPPORTING_STRUCTURES = BUILDER.comment("Minimum distance, in blocks, an orphan structure is allowed " +
                        "to be from every other supporting structure already placed, whether in a capital's realm or by another orphan faction.")
                .defineInRange("minOrphanStructureDistanceFromSupportingStructures", 250, 0, 1_000_000);

        FALLBACK_WORLD_BORDER_RADIUS = BUILDER.comment("Radius, in blocks, of the playable world along both axes, used only when Than's Worldborder " +
                        "mod (https://github.com/thanwiggins/worldborder) isn't installed or its custom overworld border is disabled.")
                .defineInRange("fallbackWorldBorderRadius", 1000, 1, 1_000_000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
