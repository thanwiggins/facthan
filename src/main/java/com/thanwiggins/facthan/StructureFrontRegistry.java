package com.thanwiggins.facthan;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

// Datapack-driven, mod-agnostic registry of "which local direction is the front of this
// structure" - loads every data/<namespace>/structure_fronts/*.json across all active datapacks
// (same reload-listener merge behavior as FactionRegistry), where the file's own resource location
// (<namespace>:<path>) must match a worldgen/structure id.
//
// A structure with no entry here simply never gets a road endpoint (see FrontAnchor) - this is
// deliberate, not an error, so any structure/mod that hasn't opted in is silently skipped rather
// than guessed at.
//
// Only a *local* direction is stored - the structure's real-world-facing direction depends on the
// random Rotation actually rolled for it at generation time, which only CapitalRealmPlanner knows
// (see FrontAnchor.compute).
@Mod.EventBusSubscriber(modid = FacthanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class StructureFrontRegistry extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StructureFrontRegistry INSTANCE = new StructureFrontRegistry();

    private static final Codec<Direction> DIRECTION_CODEC = Codec.STRING.comapFlatMap(
            name -> {
                for (Direction direction : Direction.values()) {
                    if (direction.getSerializedName().equals(name) && direction.getAxis().isHorizontal()) {
                        return com.mojang.serialization.DataResult.success(direction);
                    }
                }
                return com.mojang.serialization.DataResult.error(() -> "Not a horizontal direction: " + name);
            },
            Direction::getSerializedName
    );

    private static final Codec<Direction> ENTRY_CODEC = DIRECTION_CODEC.fieldOf("direction").codec();

    private volatile Map<ResourceLocation, Direction> frontsById = Map.of();

    private StructureFrontRegistry() {
        super(new Gson(), "structure_fronts");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Direction> byId = new HashMap<>();

        object.forEach((id, json) -> ENTRY_CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(
                error -> LOGGER.error("Skipping invalid structure front {}: {}", id, error)
        ).ifPresent(direction -> byId.put(id, direction)));

        this.frontsById = Map.copyOf(byId);
        LOGGER.info("Loaded {} structure front(s)", byId.size());
    }

    // Null if this structure has no registered front - callers must treat that as "skip this
    // structure for road purposes", not as a default direction.
    public static Direction localFront(ResourceLocation structureId) {
        return INSTANCE.frontsById.get(structureId);
    }
}
