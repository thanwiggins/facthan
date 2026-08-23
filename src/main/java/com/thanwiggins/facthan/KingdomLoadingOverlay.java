package com.thanwiggins.facthan;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Draws CapitalRealmPlanner's current step (see KingdomBootStatus) under the vanilla "Building
// world" progress bar. `value = Dist.CLIENT` keeps Forge from ever loading this class on a
// dedicated server, where LevelLoadingScreen doesn't exist and there's no player to show it to
// anyway.
@Mod.EventBusSubscriber(modid = FacthanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class KingdomLoadingOverlay {
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof LevelLoadingScreen screen)) return;

        String step = KingdomBootStatus.get();
        if (step == null) return;

        event.getGuiGraphics().drawCenteredString(screen.getMinecraft().font, step,
                screen.width / 2, screen.height / 2 + 70, 0xFFFFFF);
    }
}
