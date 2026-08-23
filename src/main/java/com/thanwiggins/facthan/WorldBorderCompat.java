package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

// Optional soft dependency on Than's Worldborder (https://github.com/thanwiggins/worldborder,
// mod id "worldborder"). That mod does NOT touch vanilla's own WorldBorder - it enforces a
// completely separate, custom bound via public static fields on its own
// com.natamus.worldborder.config.ConfigHandler class (verified against its 1.20.1 source) and a
// mixin that cancels structure generation outside it. When it's present and its custom overworld
// border is enabled, we read those bounds directly by reflection so this mod can compile and run
// without ever depending on Worldborder's classes; otherwise we fall back to our own configured
// default radius.
public final class WorldBorderCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "worldborder";
    private static final String CONFIG_HANDLER_CLASS = "com.natamus.worldborder.config.ConfigHandler";

    public record Bounds(int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(int blockX, int blockZ) {
            return blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ;
        }
    }

    private WorldBorderCompat() {}

    public static Bounds overworldBounds() {
        Bounds fromWorldborder = tryReadWorldborderBounds();
        if (fromWorldborder != null) return fromWorldborder;

        int radius = KingdomConfig.FALLBACK_WORLD_BORDER_RADIUS.get();
        return new Bounds(-radius, radius, -radius, radius);
    }

    private static Bounds tryReadWorldborderBounds() {
        if (!ModList.get().isLoaded(MOD_ID)) return null;

        try {
            Class<?> configHandler = Class.forName(CONFIG_HANDLER_CLASS);
            boolean enabled = configHandler.getField("enableCustomOverworldBorder").getBoolean(null);
            if (!enabled) return null;

            int posX = configHandler.getField("overworldBorderPositiveX").getInt(null);
            int negX = configHandler.getField("overworldBorderNegativeX").getInt(null);
            int posZ = configHandler.getField("overworldBorderPositiveZ").getInt(null);
            int negZ = configHandler.getField("overworldBorderNegativeZ").getInt(null);
            return new Bounds(negX, posX, negZ, posZ);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Worldborder is installed but its config couldn't be read (has its internal API changed?) - " +
                    "falling back to Facthan's own configured world border radius.", e);
            return null;
        }
    }
}
