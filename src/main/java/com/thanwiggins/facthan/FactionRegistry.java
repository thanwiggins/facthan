package com.thanwiggins.facthan;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Datapack-driven registry of political factions - loads every data/<namespace>/political_factions/
// *.json across all active datapacks (vanilla's own reload-listener merge behavior, so any number of
// datapacks can each add factions). A faction registered here is only eligible to be assigned
// Voronoi territory (PoliticalMapService) - which structure_sets belong to it is declared separately,
// on the structure_set's own "mcaichat:faction_spread" placement (see FactionStructurePlacement).
@Mod.EventBusSubscriber(modid = FacthanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FactionRegistry extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FactionRegistry INSTANCE = new FactionRegistry();

    private volatile Map<ResourceLocation, Faction> factionsById = Map.of();
    // Sorted independently of datapack load order, so cell->faction assignment (PoliticalMapService)
    // is stable across restarts and hosts even if datapacks are re-ordered or added.
    private volatile List<ResourceLocation> orderedFactionIds = List.of();

    private FactionRegistry() {
        super(new Gson(), "political_factions");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Faction> byId = new HashMap<>();

        object.forEach((id, json) -> Faction.CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(
                error -> LOGGER.error("Skipping invalid political faction {}: {}", id, error)
        ).ifPresent(faction -> byId.put(id, faction)));

        List<ResourceLocation> ordered = new ArrayList<>(byId.keySet());
        ordered.sort(ResourceLocation::compareTo);

        this.factionsById = Map.copyOf(byId);
        this.orderedFactionIds = List.copyOf(ordered);

        LOGGER.info("Loaded {} political faction(s)", byId.size());
    }

    public static Map<ResourceLocation, Faction> factions() {
        return INSTANCE.factionsById;
    }

    public static List<ResourceLocation> orderedFactionIds() {
        return INSTANCE.orderedFactionIds;
    }
}
