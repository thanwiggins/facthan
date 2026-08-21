package com.thanwiggins.facthan;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(FacthanMod.MODID)
public class FacthanMod {
    public static final String MODID = "facthan";

    public FacthanMod() {
        // COMMON, not CLIENT - structure placement only ever runs on whichever process is actually
        // generating the chunk (the dedicated server, or the integrated server in singleplayer), so
        // only that process's own copy of these settings can ever matter. See PoliticalConfig.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PoliticalConfig.SPEC);

        ModStructurePlacementTypes.PLACEMENT_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
