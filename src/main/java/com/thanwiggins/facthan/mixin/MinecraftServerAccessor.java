package com.thanwiggins.facthan.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Read-only access to MinecraftServer's protected storageSource field, used by
// MinecraftServerMixin to delete an incomplete save after a failed capital search.
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess facthan$getStorageSource();
}
