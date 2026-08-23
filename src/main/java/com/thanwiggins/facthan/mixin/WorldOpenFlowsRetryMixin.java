package com.thanwiggins.facthan.mixin;

import com.thanwiggins.facthan.KingdomWorldRetry;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalLong;
import java.util.function.Function;

// By the time createFreshLevel's own call into Minecraft#doWorldLoad returns here (its wait loop
// exits normally now that MinecraftCrashRedirectMixin swallows our specific crash), we're back on
// the render thread with nothing left blocked on the stack - safe to recurse into
// createFreshLevel again with a freshly-rolled seed if KingdomWorldRetry says a flush is pending.
// Everything else (name, level settings, dimensions) is reused unchanged, matching "restarts with
// the same parameters the user defined."
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsRetryMixin {
    @Inject(method = "createFreshLevel", at = @At("TAIL"))
    private void facthan$retryWithNewSeedIfNeeded(String name, LevelSettings levelSettings, WorldOptions worldOptions,
            Function<RegistryAccess, WorldDimensions> dimensionsFunction, CallbackInfo ci) {
        if (!KingdomWorldRetry.consumeRetry()) return;

        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        WorldOptions newSeedOptions = worldOptions.withSeed(OptionalLong.of(WorldOptions.randomSeed()));
        self.createFreshLevel(name, levelSettings, newSeedOptions, dimensionsFunction);
    }
}
