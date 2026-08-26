package com.thanwiggins.facthan.mixin;

import com.thanwiggins.facthan.CapitalRealmPlanner;
import com.thanwiggins.facthan.KingdomBootStatus;
import com.thanwiggins.facthan.KingdomGenerationAbortedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
//
// Deliberately does NOT delete the incomplete save here - this runs on the server's OWN thread,
// still inside its normal startup/shutdown sequence, so deleting the level out from under it races
// vanilla's own subsequent "save the world" step on the way down (see MinecraftCrashRedirectMixin,
// which defers that cleanup until the old server's thread has actually finished dying).
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void facthan$planKingdom(ChunkProgressListener listener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;

        try {
            CapitalRealmPlanner.planAndForceGenerate(server);
        } catch (KingdomGenerationAbortedException e) {
            KingdomBootStatus.clear();
            throw e;
        }
    }
}
