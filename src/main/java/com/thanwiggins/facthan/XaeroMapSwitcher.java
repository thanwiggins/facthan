package com.thanwiggins.facthan;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Drives XaeroWorldMapBridge once per singleplayer world, right after the player actually spawns in
// (Xaero's own map session isn't up yet during the "Building world" screen, so this can't run any
// earlier than a normal client tick after joining). `value = Dist.CLIENT` keeps Forge from ever
// loading this on a dedicated server - Xaero's per-player map cache is client-only there anyway (see
// XaeroWorldMapBridge), and a dedicated server has no Minecraft/Level client classes to reference.
//
// Only ever reads KingdomSavedData, never writes it - "should we bother switching Xaero's map at
// all" is answered by whether this world's kingdom generation ever placed a capital, and that data
// is permanently finalized (see CapitalRealmPlanner) long before the player is ever in the world to
// trigger this tick handler, so reading it directly from the client thread here is safe.
@Mod.EventBusSubscriber(modid = FacthanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class XaeroMapSwitcher {
    // Tracks the last Overworld this has already resolved (switched, found already switched, or
    // given up on) so it stops polling once there's nothing left to do - compared by identity since
    // a fresh ServerLevel is created every time a world is (re)loaded.
    private static ServerLevel resolvedOverworld;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;

        MinecraftServer integratedServer = mc.getSingleplayerServer();
        if (integratedServer == null) return;

        ServerLevel overworld = integratedServer.overworld();
        if (overworld == resolvedOverworld) return;

        KingdomSavedData data = KingdomSavedData.get(overworld);
        if (!data.isFinalized() || data.capitals().isEmpty()) {
            resolvedOverworld = overworld;
            return;
        }

        if (XaeroWorldMapBridge.trySwitchOverworldToCustomMap()) {
            resolvedOverworld = overworld;
        }
    }
}
