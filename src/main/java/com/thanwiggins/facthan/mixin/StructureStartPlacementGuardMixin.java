package com.thanwiggins.facthan.mixin;

import com.thanwiggins.facthan.ForcedPlacementGuard;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// CapitalRealmPlanner.forceGenerate now registers a structure's start onto its touched chunks
// while they're still at ChunkStatus.EMPTY (see ForcedPlacementGuard's own comment for why) so that
// terrain_adaptation/beard_box - which reads the exact same per-chunk structure-reference data
// vanilla's own auto-placement uses - actually sees the structure while shaping terrain. The
// unavoidable side effect: as those chunks are then forced the rest of the way to FULL, their own
// FEATURES stage (ChunkGenerator#applyBiomeDecoration) will try to call this exact same
// placeInChunk on its own, unconditionally - vanilla has no "already placed" bookkeeping anywhere
// in this path to prevent it. Cancelling that specific, unwanted call - and only that one, only for
// a start CapitalRealmPlanner itself marked pending - is what actually prevents the entity
// duplication a naive "just register early" change would otherwise reintroduce.
@Mixin(StructureStart.class)
public abstract class StructureStartPlacementGuardMixin {
    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void facthan$cancelIfPending(WorldGenLevel level, StructureManager structureManager,
            ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunkPos, CallbackInfo ci) {
        if (ForcedPlacementGuard.isPending((StructureStart) (Object) this)) {
            ci.cancel();
        }
    }
}
