package com.thanwiggins.facthan.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Skips vanilla's animated per-chunk-status square grid on the "Building world" screen, in keeping
// with this mod's own habit of trimming what that screen reveals (see the KingdomBootStatus/
// KingdomLoadingOverlay pair, which already replaces it with a faction-agnostic status line) -
// everything else about the screen (background, percentage text, our own overlay) is untouched.
@Mixin(LevelLoadingScreen.class)
public abstract class HideChunkGridMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;renderChunks(Lnet/minecraft/client/gui/GuiGraphics;"
                    + "Lnet/minecraft/server/level/progress/StoringChunkProgressListener;IIII)V"))
    private void facthan$hideChunkGrid(GuiGraphics guiGraphics, StoringChunkProgressListener listener,
                                         int minX, int minY, int maxX, int maxY) {
        // no-op - the redirect handler's static/instance modifier must match the enclosing method
        // being injected into (render, an instance method), not the redirected call target
        // (renderChunks, which happens to be static) - that mismatch is what failed to apply.
    }
}
