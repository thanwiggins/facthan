package com.thanwiggins.facthan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.Optional;

// The "mcaichat:faction_spread" structure_set placement type - a datapack author swaps this in for
// "minecraft:random_spread" on any structure_set they want kingdom-bound, adding one field ("faction")
// on top of random_spread's usual spacing/separation/salt/etc.
//
// RandomSpreadStructurePlacement isn't final and its isPlacementChunk is protected, so this
// subclasses it and layers the Voronoi political check (PoliticalMapService) on top of vanilla's own
// candidate-chunk grid via super.isPlacementChunk, rather than reimplementing that spacing/separation
// algorithm from scratch. Everything downstream of a true result here - biome suitability, and any
// companion mod's own checks (Structurify included) - runs completely unmodified.
//
// The codec can't reuse StructurePlacement.placementCodec()'s P5 the way vanilla's own subclasses do
// (DataFixerUpper's Products chain tops out at P8, and 5 inherited fields + 4 of ours is a P9), so the
// 5 base fields below are inlined from placementCodec's own definitions instead of composed with it.
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
                    ResourceLocation.CODEC.fieldOf("faction").forGetter(FactionStructurePlacement::faction)
            ).apply(instance, FactionStructurePlacement::new));

    private final ResourceLocation faction;

    public FactionStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod, float frequency, int salt,
                                      Optional<ExclusionZone> exclusionZone, int spacing, int separation, RandomSpreadType spreadType,
                                      ResourceLocation faction) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, spacing, separation, spreadType);
        this.faction = faction;
    }

    public ResourceLocation faction() {
        return faction;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState structureState, int chunkX, int chunkZ) {
        if (!super.isPlacementChunk(structureState, chunkX, chunkZ)) return false;

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        return PoliticalMapService.isAllowedAt(structureState.getLevelSeed(), faction,
                chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
    }

    @Override
    public StructurePlacementType<?> type() {
        return ModStructurePlacementTypes.FACTION_SPREAD.get();
    }
}
