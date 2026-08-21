package com.thanwiggins.facthan;

// Deterministic Voronoi/Worley cellular noise over the world's political map. Every value here is a
// pure function of (worldSeed, cellSize, blockX, blockZ) - nothing is ever stored to disk, so the
// entire map for a given seed can be recomputed identically at any time (a future Xaero overlay
// included) without persisting a single chunk of political data.
public final class PoliticalMap {
    private PoliticalMap() {}

    public record CellResult(long cellId, double nearestSiteDist, double secondNearestSiteDist) {
        // (F2 - F1) / 2 is the standard cellular-noise approximation for "distance to the nearest
        // Voronoi edge" - exact on the line segment between the two nearest sites, an underestimate
        // off it, which is more than good enough for a "how deep into no-man's-land" buffer check.
        public double approxDistanceToBorder() {
            return (secondNearestSiteDist - nearestSiteDist) / 2.0;
        }
    }

    public static CellResult lookupCell(long worldSeed, int cellSize, int blockX, int blockZ) {
        int gridX = Math.floorDiv(blockX, cellSize);
        int gridZ = Math.floorDiv(blockZ, cellSize);

        long bestCellId = 0;
        double bestDist = Double.MAX_VALUE;
        double secondBestDist = Double.MAX_VALUE;

        // A site can only ever be the nearest one from within its own grid cell or one of the 8
        // immediate neighbors, so checking a 3x3 neighborhood is sufficient regardless of jitter.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cellGridX = gridX + dx;
                int cellGridZ = gridZ + dz;
                long[] site = jitteredSite(worldSeed, cellSize, cellGridX, cellGridZ);
                double dist = distance(blockX, blockZ, site[0], site[1]);

                if (dist < bestDist) {
                    secondBestDist = bestDist;
                    bestDist = dist;
                    bestCellId = packCellId(cellGridX, cellGridZ);
                } else if (dist < secondBestDist) {
                    secondBestDist = dist;
                }
            }
        }

        return new CellResult(bestCellId, bestDist, secondBestDist);
    }

    // Deterministically jitters each grid cell's Voronoi site within its own cell bounds, seeded from
    // (worldSeed, cellGridX, cellGridZ) - same inputs always produce the same site, so the whole map
    // is reproducible without storing anything, and unique per world seed.
    private static long[] jitteredSite(long worldSeed, int cellSize, int cellGridX, int cellGridZ) {
        long hash = mix(worldSeed, cellGridX, cellGridZ);
        double jitterX = ((hash >>> 32) & 0xFFFFFFFFL) / 4294967295.0;
        double jitterZ = (hash & 0xFFFFFFFFL) / 4294967295.0;

        long siteX = (long) cellGridX * cellSize + (long) (jitterX * cellSize);
        long siteZ = (long) cellGridZ * cellSize + (long) (jitterZ * cellSize);
        return new long[]{siteX, siteZ};
    }

    private static double distance(long x1, long z1, long x2, long z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long packCellId(int cellGridX, int cellGridZ) {
        return (((long) cellGridX) << 32) ^ (cellGridZ & 0xFFFFFFFFL);
    }

    // SplitMix64-style mix - cheap, and decorrelates worldSeed/cellGridX/cellGridZ well enough that
    // neighboring cells don't end up with visibly correlated jitter.
    private static long mix(long worldSeed, int cellGridX, int cellGridZ) {
        long h = worldSeed;
        h ^= (long) cellGridX * 0x9E3779B97F4A7C15L;
        h ^= (long) cellGridZ * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    // Same mixing family as above, used to fold a cell id back into an index over the configured
    // faction list - kept separate from jitteredSite's mix so a change to one doesn't reshuffle
    // the other.
    public static long mixCellAndSeed(long worldSeed, long cellId) {
        long h = worldSeed ^ Long.rotateLeft(cellId, 17);
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }
}
