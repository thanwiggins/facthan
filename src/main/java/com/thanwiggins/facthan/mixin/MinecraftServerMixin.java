package com.thanwiggins.facthan.mixin;

import com.mojang.logging.LogUtils;
import com.thanwiggins.facthan.CapitalRealmPlanner;
import com.thanwiggins.facthan.KingdomBootStatus;
import com.thanwiggins.facthan.KingdomGenerationAbortedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

// Runs the capital/realm search exactly once, right after MinecraftServer#createLevels finishes
// (so the Overworld's ChunkGenerator/StructureManager/RegistryAccess all exist) but before
// #prepareLevels starts generating any chunk - #prepareLevels is the very next thing loadLevel()
// calls, with nothing else of consequence in between, so injecting at its HEAD lands us exactly on
// that boundary. No existing Forge lifecycle event sits there: ServerAboutToStartEvent fires before
// any ServerLevel is even constructed, and ServerStartingEvent fires only after spawn chunks are
// already prepared - both were checked against the decompiled 1.20.1/Forge 47.3.0 sources before
// choosing this mixin instead.
//
// For singleplayer this all still happens while the player is watching the vanilla "Building
// world" loading screen: MinecraftServer.spin() runs the whole server startup (and so this
// injection) on a separate thread, while Minecraft#doWorldLoad's own loop just keeps ticking that
// screen until the server reports isReady() - see KingdomBootStatus for how that loop's client-side
// overlay reads our progress.
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void facthan$planKingdom(ChunkProgressListener listener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;

        try {
            CapitalRealmPlanner.planAndForceGenerate(server);
        } catch (KingdomGenerationAbortedException e) {
            KingdomBootStatus.clear();
            deleteIncompleteSave(server);
            throw e;
        }
    }

    // Best-effort - an incomplete save folder lingering on disk after a failed capital search is
    // an inconvenience for the player to clean up manually, not a correctness problem, so a failed
    // deletion here is only logged, never allowed to mask the real KingdomGenerationAbortedException.
    private void deleteIncompleteSave(MinecraftServer server) {
        try {
            ((MinecraftServerAccessor) server).facthan$getStorageSource().deleteLevel();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't clean up the incomplete world save after a failed capital search - " +
                    "you may need to delete it by hand.", e);
        }
    }
}
