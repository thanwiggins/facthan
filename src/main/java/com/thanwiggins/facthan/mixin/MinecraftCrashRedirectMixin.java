package com.thanwiggins.facthan.mixin;

import com.thanwiggins.facthan.KingdomWorldRetry;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Minecraft#doWorldLoad's own wait loop calls the static Minecraft.crash(report) - which, for a
// failure during world creation, doesn't show a friendly screen at all, it hard System.exit()s the
// whole game (see Minecraft#crash). Redirecting that one call lets us swallow it specifically for
// KingdomGenerationAbortedException (see KingdomWorldRetry) - doWorldLoad's own `return;`
// immediately after the call still runs, so the method just exits normally instead of crashing.
// Any other crash reaches the real Minecraft.crash(report) completely unchanged.
@Mixin(Minecraft.class)
public abstract class MinecraftCrashRedirectMixin {
    @Redirect(method = "doWorldLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;crash(Lnet/minecraft/CrashReport;)V"))
    private void facthan$maybeSwallowCrash(CrashReport report) {
        if (KingdomWorldRetry.shouldSwallow(report)) {
            ((MinecraftAccessor) Minecraft.getInstance()).facthan$setDelayedCrash(null);
        } else {
            Minecraft.crash(report);
        }
    }
}
