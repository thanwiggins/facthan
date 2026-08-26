package com.thanwiggins.facthan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// Where a road should actually touch a structure - the midpoint of whichever face of its real,
// already-rotated bounding box its registered local front (see StructureFrontRegistry) ends up
// facing once the structure's own random placement Rotation is applied, offset outward by
// roadAnchorOffset blocks so the road doesn't terminate inside a wall.
record FrontAnchor(BlockPos pos, Direction facing) {

    // Null if this structure has no registered front - callers must skip road-building for it
    // entirely rather than falling back to some arbitrary point.
    static FrontAnchor compute(ResourceLocation structureId, BoundingBox box, Rotation rotation, int offset) {
        Direction localFront = StructureFrontRegistry.localFront(structureId);
        if (localFront == null) return null;

        Direction worldFacing = rotation.rotate(localFront);
        int centerX = (box.minX() + box.maxX()) / 2;
        int centerZ = (box.minZ() + box.maxZ()) / 2;

        int x = switch (worldFacing) {
            case WEST -> box.minX() - offset;
            case EAST -> box.maxX() + offset;
            default -> centerX;
        };
        int z = switch (worldFacing) {
            case NORTH -> box.minZ() - offset;
            case SOUTH -> box.maxZ() + offset;
            default -> centerZ;
        };

        return new FrontAnchor(new BlockPos(x, box.minY(), z), worldFacing);
    }
}
