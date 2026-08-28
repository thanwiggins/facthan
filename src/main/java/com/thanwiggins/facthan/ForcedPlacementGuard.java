package com.thanwiggins.facthan;

import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Lets CapitalRealmPlanner register a structure's start early - onto its origin chunk, while that
// chunk is still at ChunkStatus.EMPTY, before any of the structure's other touched chunks advance -
// so that when those chunks are later forced the rest of the way to FULL, their own NOISE stage
// sees the start already registered (this is what makes terrain_adaptation/beard_box actually fire,
// see StructureStartPlacementGuardMixin's own comment for why). The unavoidable side effect of
// registering that early is that the origin chunk's own FEATURES stage will also try to auto-place
// the very same start via ChunkGenerator#applyBiomeDecoration - exactly the mechanism that caused
// the entity-duplication bug this mod already fixed once (see CapitalRealmPlanner's forceGenerate).
// This guard is how that specific auto-placement attempt gets cancelled, while still letting
// CapitalRealmPlanner's own, single, deliberate placeInChunk call through.
//
// Keyed by StructureStart identity (it doesn't override equals/hashCode, so reference identity is
// exactly what a plain ConcurrentHashMap-backed set already gives) - every call to
// Structure#generate() returns a brand new instance never shared with any other mod's or vanilla's
// own structure, so this can never affect placement of anything Facthan doesn't itself own.
// ConcurrentHashMap, not a plain HashSet, because chunk generation can run parts of its work on
// background executor threads.
// Public (not package-private) because StructureStartPlacementGuardMixin, the only other thing
// that needs this, lives in the com.thanwiggins.facthan.mixin package - a genuinely separate
// package from Java's point of view, not a nested scope of this one.
public final class ForcedPlacementGuard {
    private static final Set<StructureStart> PENDING = ConcurrentHashMap.newKeySet();

    private ForcedPlacementGuard() {}

    public static void markPending(StructureStart start) {
        PENDING.add(start);
    }

    public static boolean isPending(StructureStart start) {
        return PENDING.contains(start);
    }

    // Called once the structure's own chunks are all FULL and CapitalRealmPlanner is about to place
    // it for real - lifts the guard so its own placeInChunk calls aren't cancelled too.
    public static void allow(StructureStart start) {
        PENDING.remove(start);
    }
}
