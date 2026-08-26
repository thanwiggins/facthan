package com.thanwiggins.facthan;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Datapack-driven, mod-agnostic registry of "what does a road's inner surface, outer border, and
// bridge fill look like in this biome" - loads every data/<namespace>/road_materials/*.json across
// all active datapacks (same reload-listener merge behavior as FactionRegistry/
// StructureFrontRegistry), keyed by the file's own resource location, which must match a BIOME's
// registry id (e.g. "minecraft:plains").
//
// All three fields are optional, independently - a biome missing any of them just falls back to
// RoadBuilder's own global config block for that one field, never an error, same philosophy as
// StructureFrontRegistry.
@Mod.EventBusSubscriber(modid = FacthanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RoadMaterialRegistry extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RoadMaterialRegistry INSTANCE = new RoadMaterialRegistry();

    // A palette's own block lists, already resolved to BlockStates at datapack-reload time - any
    // field may be empty (no override for this biome), in which case RoadBuilder falls back to its
    // own global config block for that field instead.
    record RoadPalette(List<BlockState> inner, List<BlockState> outer, List<BlockState> bridge) {}

    private record PaletteEntry(List<String> inner, List<String> outer, List<String> bridge) {
        private static final Codec<PaletteEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("inner", List.of()).forGetter(PaletteEntry::inner),
                Codec.STRING.listOf().optionalFieldOf("outer", List.of()).forGetter(PaletteEntry::outer),
                Codec.STRING.listOf().optionalFieldOf("bridge", List.of()).forGetter(PaletteEntry::bridge)
        ).apply(instance, PaletteEntry::new));
    }

    private volatile Map<ResourceLocation, RoadPalette> palettesByBiome = Map.of();

    private RoadMaterialRegistry() {
        super(new Gson(), "road_materials");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, RoadPalette> byBiome = new HashMap<>();

        object.forEach((id, json) -> PaletteEntry.CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(
                error -> LOGGER.error("Skipping invalid road material palette {}: {}", id, error)
        ).ifPresent(entry -> byBiome.put(id, new RoadPalette(
                resolveBlocks(entry.inner()), resolveBlocks(entry.outer()), resolveBlocks(entry.bridge())
        ))));

        this.palettesByBiome = Map.copyOf(byBiome);
        LOGGER.info("Loaded {} road material palette(s)", byBiome.size());
    }

    private static List<BlockState> resolveBlocks(List<String> ids) {
        List<BlockState> out = new ArrayList<>();
        for (String id : ids) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                out.add(block.defaultBlockState());
            } else {
                LOGGER.error("Unknown road material block \"{}\" - skipping it.", id);
            }
        }
        return out;
    }

    // Null if this biome has no registered palette - callers must fall back to their own default,
    // never treat null as "no road material at all".
    public static RoadPalette paletteFor(ResourceLocation biomeId) {
        return INSTANCE.palettesByBiome.get(biomeId);
    }
}
