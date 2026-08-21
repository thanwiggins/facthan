package com.thanwiggins.facthan;

import net.minecraftforge.common.ForgeConfigSpec;

// Deliberately a separate COMMON-type spec, not part of Config.java's CLIENT spec - structure
// placement only ever runs on whichever process is actually generating the chunk (the dedicated
// server, or the integrated server in singleplayer), so only that process's own copy of these
// settings can ever matter. Bundling them into the CLIENT config would repeat the exact
// per-installation desync gap already tracked for Config.java's other settings (see issue #8).
public class PoliticalConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue CELL_SIZE;
    public static final ForgeConfigSpec.IntValue BORDER_BUFFER_WIDTH;

    static {
        BUILDER.push("Political Map Settings");

        ENABLED = BUILDER.comment("Whether faction-owned structure sets are restricted to their kingdom's territory at all. " +
                        "Structures with no assigned faction are never affected either way.")
                .define("politicalMapEnabled", false);

        CELL_SIZE = BUILDER.comment("Average width, in blocks, of a single Voronoi kingdom cell before jitter. Larger values " +
                        "produce fewer, bigger kingdoms; smaller values produce more, smaller ones.")
                .defineInRange("cellSize", 4000, 500, 100000);

        BORDER_BUFFER_WIDTH = BUILDER.comment("Width, in blocks, of the neutral no-man's-land straddling every kingdom border. " +
                        "A faction structure whose approximate distance to the nearest border is under this value never generates.")
                .defineInRange("borderBufferWidth", 200, 0, 50000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
