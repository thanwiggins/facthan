package com.thanwiggins.facthan;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

// Registers "facthan:faction_spread" into vanilla's own (non-datapack) STRUCTURE_PLACEMENT
// registry - the same registry random_spread/concentric_rings live in - so a structure_set JSON's
// "placement": {"type": "facthan:faction_spread", ...} resolves to FactionStructurePlacement.CODEC.
public class ModStructurePlacementTypes {
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENT_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, FacthanMod.MODID);

    public static final RegistryObject<StructurePlacementType<FactionStructurePlacement>> FACTION_SPREAD =
            PLACEMENT_TYPES.register("faction_spread", () -> () -> FactionStructurePlacement.CODEC);
}
