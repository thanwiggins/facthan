package com.thanwiggins.facthan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.Optional;

// The "facthan:faction_spread" structure_set placement type - a datapack author swaps this in for
// "minecraft:random_spread" on any structure_set they want kingdom-bound, adding two fields
// ("faction", "is_capital") on top of random_spread's usual spacing/separation/salt/etc.
//
// This placement type no longer does any Voronoi territory check (see the retired PoliticalMap /
// PoliticalMapService) - it now backs CapitalRealmPlanner's guarantee that a faction's capital and
// every one of its supporting structures are placed ONLY by that routine, never by vanilla's normal
// per-chunk generation: if this faction was finalized as a capital-faction this world (see
// KingdomSavedData), every one of its structure_sets is unconditionally vetoed here, since
// CapitalRealmPlanner already force-generated them all directly. A faction that wasn't selected
// this world falls through to plain vanilla random_spread behavior, completely unrestricted.
//
// RandomSpreadStructurePlacement isn't final and its isPlacementChunk is protected, so this
// subclasses it and layers the finalized-faction veto on top of vanilla's own candidate-chunk grid
// via super.isPlacementChunk, rather than reimplementing that spacing/separation algorithm from
// scratch. Everything downstream of a true result here - biome suitability, and any companion mod's
// own checks (Structurify included) - runs completely unmodified.
public class FactionStructurePlacement extends RandomSpreadStructurePlacement {
    public static final Codec<FactionStructurePlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Vec3i.offsetCodec(16).optionalFieldOf("locate_offset", Vec3i.ZERO).forGetter(FactionStructurePlacement::locateOffset),
                    FrequencyReductionMethod.CODEC.optionalFieldOf("frequency_reduction_method", FrequencyReductionMethod.DEFAULT).forGetter(FactionStructurePlacement::frequencyReductionMethod),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("frequency", 1.0f).forGetter(FactionStructurePlacement::frequency),
                    ExtraCodecs.NON_NEGATIVE_INT.fieldOf("salt").forGetter(FactionStructurePlacement::salt),
                    ExclusionZone.CODEC.optionalFieldOf("exclusion_zone").forGetter(FactionStructurePlacement::exclusionZone),
                    Codec.intRange(0, 4096).fieldOf("spacing").forGetter(RandomSpreadStructurePlacement::spacing),
                    Codec.intRange(0, 4096).fieldOf("separation").forGetter(RandomSpreadStructurePlacement::separation),
                    RandomSpreadType.CODEC.optionalFieldOf("spread_type", RandomSpreadType.LINEAR).forGetter(RandomSpreadStructurePlacement::spreadType),
                    ResourceLocation.CODEC.fieldOf("faction").forGetter(FactionStructurePlacement::faction),
                    Codec.BOOL.optionalFieldOf("is_capital", false).forGetter(FactionStructurePlacement::isCapital)
            ).apply(instance, FactionStructurePlacement::new));

    private final ResourceLocation faction;
    private final boolean isCapital;

    public FactionStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod, float frequency, int salt,
                                      Optional<ExclusionZone> exclusionZone, int spacing, int separation, RandomSpreadType spreadType,
                                      ResourceLocation faction, boolean isCapital) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, spacing, separation, spreadType);
        this.faction = faction;
        this.isCapital = isCapital;
    }

    public ResourceLocation faction() {
        return faction;
    }

    public boolean isCapital() {
        return isCapital;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState structureState, int chunkX, int chunkZ) {
        if (!super.isPlacementChunk(structureState, chunkX, chunkZ)) return false;

        return !KingdomSavedData.isFinalizedFactionCached(faction);
    }

    @Override
    public StructurePlacementType<?> type() {
        return ModStructurePlacementTypes.FACTION_SPREAD.get();
    }
}
