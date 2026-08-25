package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

// Reflectively drives Xaero's World Map into creating and switching to a brand new, empty "custom
// map" for the Overworld - exactly what a player gets by hand via its own "Map Switching Options" ->
// "Create New Map" -> "Confirm" flow (see xaero.map.gui.GuiMapName and xaero.map.gui.GuiMapSwitching
// in a decompile of xaeroworldmap-forge-1.20.1-1.45.0.jar). Done this way, rather than by us reading
// or deleting Xaero's own cache files, because in singleplayer Xaero's World Map doesn't cache "what
// the player has explored" at all by default - see xaero.map.world.MapDimension#isUsingWorldSave(),
// which is true for any singleplayer dimension with no custom "multiworld" selected. While true, the
// map is drawn straight from the world save's own region files every time (see
// xaero.map.file.MapSaveLoad#detectRegions/#buildRegion), so a force-generated capital is visible the
// instant its chunk exists on disk, regardless of whether the player has ever been near it. Once a
// dimension has a real (non-empty) multiworld selected, isUsingWorldSave() becomes false and Xaero
// switches to building the map purely from chunks the client has actually loaded - the same as it
// does for a multiplayer server - which is the behavior this mod actually wants.
//
// There is no supported API for any of this - every method below is invoked through reflection
// against Xaero's own internal (non-API) classes, matched against xaeroworldmap 1.45.0 for Minecraft
// 1.20.1. A future Xaero update could rename or restructure any of it, so every failure mode here is
// treated as "give up quietly and leave the player's map alone" rather than crashing anything.
public final class XaeroWorldMapBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "xaeroworldmap";
    private static final String CUSTOM_MAP_NAME = "World Map";
    private static final String CUSTOM_MAP_ID_BASE = "cm$worldmap";

    // Sticky for the rest of this game session once we know there's nothing more to do here -
    // either Xaero's World Map isn't installed, or something about its internals didn't match what
    // this class expects. Deliberately not reset per-world: if the internals didn't match once,
    // retrying on every subsequent world load in the same session would just fail the same way.
    private static boolean unavailable;

    private XaeroWorldMapBridge() {}

    // Returns true once there's nothing left to try for the current Overworld - either the switch
    // just succeeded, it was already done (including by the player themselves), or this gave up
    // permanently. Returns false only to mean "try again next tick" (Xaero's own map session isn't
    // fully up yet right after joining) - callers should stop polling once this returns true.
    public static boolean trySwitchOverworldToCustomMap() {
        if (unavailable) return true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            unavailable = true;
            return true;
        }

        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");

            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null || !(boolean) worldMapSessionClass.getMethod("isUsable").invoke(session)) {
                return false;
            }

            Object mapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (!(boolean) mapProcessorClass.getMethod("isMapWorldUsable").invoke(mapProcessor)) {
                return false;
            }

            Object mapWorld = mapProcessorClass.getMethod("getMapWorld").invoke(mapProcessor);
            Object currentDimId = mapWorldClass.getMethod("getCurrentDimensionId").invoke(mapWorld);
            if (!Level.OVERWORLD.equals(currentDimId)) {
                return false;
            }

            Object dimension = mapWorldClass.getMethod("getCurrentDimension").invoke(mapWorld);
            if (dimension == null) return false;

            if (!(boolean) mapDimensionClass.getMethod("isUsingWorldSave").invoke(dimension)) {
                return true; // already switched - nothing left to do
            }

            // isUsingWorldSave() being true here is ambiguous - it could mean nothing has ever been
            // confirmed for this dimension, OR it could mean something WAS already confirmed (this
            // session, or a prior one - "confirmedMultiworld" is persisted to dimension_config.txt
            // and correctly restored on load, see MapDimension#resetCustomMultiworldUnsynced) but
            // Xaero's own background MapProcessor thread hasn't promoted it into the field
            // isUsingWorldSave() actually reads (currentMultiworld) yet - that promotion only
            // happens inside MapProcessor#updateWorld -> updateWorldSynced ->
            // MapWorld#switchToFutureUnsynced, running continuously on Xaero's own processing loop,
            // entirely independent of this method. The previous version of this check couldn't tell
            // those two cases apart, so on every subsequent world load it created ANOTHER brand new
            // custom map before that background promotion had a chance to catch up - that was the "a
            // new World Map every load" bug. hasConfirmedMultiworld() (confirmedMultiworld != null)
            // is the reliable signal: if something's already confirmed, just keep polling instead of
            // creating a duplicate.
            if ((boolean) mapDimensionClass.getMethod("hasConfirmedMultiworld").invoke(dimension)) {
                return false;
            }

            boolean uiPaused = (boolean) mapProcessorClass.getMethod("isUIPaused").invoke(mapProcessor);
            boolean waitingForWorldUpdate = (boolean) mapProcessorClass.getMethod("isWaitingForWorldUpdate").invoke(mapProcessor);
            if (uiPaused || waitingForWorldUpdate) return false;

            Object uiSync = mapProcessorClass.getField("uiSync").get(mapProcessor);
            synchronized (uiSync) {
                createCustomMap(mapDimensionClass, dimension);
            }

            boolean confirmed = (boolean) mapProcessorClass.getMethod("confirmMultiworld", mapDimensionClass)
                    .invoke(mapProcessor, dimension);
            if (confirmed) {
                LOGGER.info("Switched Xaero's World Map to a fresh custom map so force-generated capitals/realms " +
                        "stay hidden until explored.");
            } else {
                LOGGER.warn("Created a custom Xaero World Map but couldn't confirm it as active this tick - " +
                        "will retry.");
                return false;
            }

            return true;
        } catch (ReflectiveOperationException | ClassCastException | IOException e) {
            LOGGER.warn("Couldn't switch Xaero's World Map to a custom map - its internals may have changed since " +
                    "this was written. Force-generated capitals/realms may be visible on the map before being " +
                    "explored; you can work around this yourself via Xaero's own \"Map Switching Options\".", e);
            unavailable = true;
            return true;
        }
    }

    // Mirrors xaero.map.gui.GuiMapName's confirm handler for the "Create New Map" option exactly -
    // same sanitized id derivation, same collision-avoidance loop, same folder creation.
    private static void createCustomMap(Class<?> mapDimensionClass, Object dimension) throws ReflectiveOperationException, IOException {
        Method addMultiworldChecked = mapDimensionClass.getMethod("addMultiworldChecked", String.class);
        String mwId = CUSTOM_MAP_ID_BASE;
        int suffix = 1;
        while (!(boolean) addMultiworldChecked.invoke(dimension, mwId)) {
            suffix++;
            mwId = CUSTOM_MAP_ID_BASE + suffix;
        }

        Path mainFolder = (Path) mapDimensionClass.getMethod("getMainFolderPath").invoke(dimension);
        Files.createDirectories(mainFolder.resolve(mwId));

        mapDimensionClass.getMethod("setMultiworldUnsynced", String.class).invoke(dimension, mwId);
        mapDimensionClass.getMethod("setMultiworldName", String.class, String.class).invoke(dimension, mwId, CUSTOM_MAP_NAME);
        mapDimensionClass.getMethod("saveConfigUnsynced").invoke(dimension);
    }
}
