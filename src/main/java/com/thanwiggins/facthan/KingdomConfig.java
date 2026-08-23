package com.thanwiggins.facthan;

import net.minecraftforge.common.ForgeConfigSpec;

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

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
