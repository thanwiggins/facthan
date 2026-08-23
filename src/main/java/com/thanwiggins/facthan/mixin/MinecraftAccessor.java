package com.thanwiggins.facthan.mixin;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

// Lets MinecraftCrashRedirectMixin clear Minecraft's private delayedCrash field after swallowing
// a KingdomGenerationAbortedException - otherwise it would stay set and get picked up by
// Minecraft#run()'s own separate "is a crash pending" check on the very next frame.
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("delayedCrash")
    void facthan$setDelayedCrash(Supplier<CrashReport> delayedCrash);
}
