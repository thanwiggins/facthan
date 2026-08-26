package com.thanwiggins.facthan.mixin;

import com.mojang.logging.LogUtils;
import com.thanwiggins.facthan.KingdomWorldRetry;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

// Minecraft#doWorldLoad's own wait loop calls the static Minecraft.crash(report) - which, for a
// failure during world creation, doesn't show a friendly screen at all, it hard System.exit()s the
// whole game (see Minecraft#crash). Redirecting that one call lets us swallow it specifically for
// KingdomGenerationAbortedException (see KingdomWorldRetry) - doWorldLoad's own `return;`
// immediately after the call still runs, so the method just exits normally instead of crashing.
// Any other crash reaches the real Minecraft.crash(report) completely unchanged.
@Mixin(Minecraft.class)
public abstract class MinecraftCrashRedirectMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    // MinecraftServer#runServer's own crash handler calls onServerCrash (which is what lets this
    // redirect fire at all) BEFORE it calls stopServer() - confirmed by reading its bytecode - so
    // the render thread can reach here while the old server is still mid-shutdown, still holding
    // its level's file lock. A bounded wait, not unlimited: if something else is also stuck, we'd
    // rather eventually retry anyway than hang forever.
    private static final long OLD_SERVER_SHUTDOWN_TIMEOUT_MS = 10_000;

    @Redirect(method = "doWorldLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;crash(Lnet/minecraft/CrashReport;)V"))
    private void facthan$maybeSwallowCrash(CrashReport report) {
        if (KingdomWorldRetry.shouldSwallow(report)) {
            Minecraft minecraft = Minecraft.getInstance();
            facthan$awaitOldServerAndCleanUp(minecraft.getSingleplayerServer());
            ((MinecraftAccessor) minecraft).facthan$setDelayedCrash(null);
        } else {
            Minecraft.crash(report);
        }
    }

    // Recursing straight into a fresh createFreshLevel attempt (see WorldOpenFlowsRetryMixin) reuses
    // the SAME level name the old, crashed server was just using - if that old server hasn't
    // actually finished releasing its level's lock yet, the new attempt's own storage-access call
    // contends for it and can hang indefinitely with nothing left to log. Blocking here until the
    // old server's thread has genuinely terminated - then, and only then, deleting its now safely
    // abandoned incomplete save - avoids both that hang and the FileNotFoundException cascade from
    // deleting a save while the old server was still trying to write into it on its own way down.
    private static void facthan$awaitOldServerAndCleanUp(IntegratedServer oldServer) {
        if (oldServer == null) return;

        Thread serverThread = oldServer.getRunningThread();
        if (serverThread != null) {
            try {
                serverThread.join(OLD_SERVER_SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            ((MinecraftServerAccessor) oldServer).facthan$getStorageSource().deleteLevel();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't clean up the incomplete world save after a failed capital search - " +
                    "you may need to delete it by hand.", e);
        }
    }
}
