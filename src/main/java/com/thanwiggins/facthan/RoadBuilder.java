package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

// The road-building step at the tail of realm generation (see CapitalRealmPlanner) - connects a
// capital's FrontAnchor to each of its realm's supporting structures' FrontAnchors with a
// terrain-following road, borrowing RoadWeaver's high-level ideas (coarse-grid A*, slope-clamped
// height smoothing) without depending on it or copying its code.
//
// Pathfinding samples terrain height via ChunkGenerator#getBaseHeight, which - like the
// "project_start_to_heightmap" a structure_set itself uses - never needs a chunk to actually exist,
// so searching a wide area costs nothing but CPU. Only once a route is finalized do we force the
// handful of chunks it actually crosses to FULL (the same overworld.getChunk(x, z) technique
// CapitalRealmPlanner.forceGenerate already uses) and pave it.
final class RoadBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int COARSE_STEP = 16;
    private static final int FINE_STEP = 4; // terrain-sampling/smoothing resolution only - see densifyForPaint for the actual paint resolution
    private static final int MAX_NODE_EXPANSIONS = 8000;
    private static final double ELEVATION_WEIGHT = 1.0;
    private static final int BLOCKED_BOX_MARGIN = 2;
    private static final int HEADROOM = 5;

    // Everything RoadBuilder needs that's constant for one whole realm's worth of roads - built
    // once in connectRealm so buildRoad/paveColumn don't have to carry a long, growing parameter
    // list every time a new road-styling knob gets added. inner/outer/bridge are single global
    // blocks, not per-biome - there's no longer any variation to look up mid-paving.
    private record RoadContext(BlockState inner, BlockState outer, BlockState bridge,
                                int width, int slopeRise, int slopeRun, int pierInterval, int pierMaxHeight) {}

    private RoadBuilder() {}

    static void connectRealm(ServerLevel overworld, ResourceLocation faction, FrontAnchor capitalAnchor,
                              List<FrontAnchor> supportingAnchors, List<BoundingBox> blockedBoxes) {
        if (capitalAnchor == null || supportingAnchors.isEmpty()) return;

        BlockState inner = resolveBlock(KingdomConfig.ROAD_INNER_BLOCK.get(), Blocks.DIRT_PATH);
        BlockState outer = resolveBlock(KingdomConfig.ROAD_OUTER_BLOCK.get(), Blocks.COBBLESTONE);
        BlockState bridge = resolveBlock(KingdomConfig.ROAD_BRIDGE_BLOCK.get(), Blocks.OAK_PLANKS);

        RoadContext ctx = new RoadContext(inner, outer, bridge, KingdomConfig.ROAD_WIDTH.get(),
                KingdomConfig.ROAD_MAX_SLOPE_RISE.get(), KingdomConfig.ROAD_MAX_SLOPE_RUN.get(),
                KingdomConfig.ROAD_BRIDGE_PIER_INTERVAL.get(), KingdomConfig.ROAD_BRIDGE_PIER_MAX_HEIGHT.get());

        ServerChunkCache chunkSource = overworld.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();

        Set<Long> forcedChunks = new HashSet<>();
        for (FrontAnchor supporting : supportingAnchors) {
            if (supporting == null) continue;
            boolean built = buildRoad(overworld, generator, randomState, capitalAnchor.pos(), supporting.pos(),
                    blockedBoxes, ctx, forcedChunks);
            if (!built) {
                LOGGER.warn("Gave up on a road for {} after exceeding the pathfinding search budget.", faction);
            }
        }
    }

    private static boolean buildRoad(ServerLevel overworld, ChunkGenerator generator, RandomState randomState,
                                       BlockPos from, BlockPos to, List<BoundingBox> blockedBoxes,
                                       RoadContext ctx, Set<Long> forcedChunks) {
        List<int[]> coarse = aStar(generator, randomState, overworld, from.getX(), from.getZ(), to.getX(), to.getZ(), blockedBoxes);
        if (coarse == null) return false;

        // Snap the endpoints back to the exact anchors - the grid search worked in COARSE_STEP
        // cells, but the road itself should terminate precisely where FrontAnchor said to.
        coarse.set(0, new int[]{from.getX(), from.getZ()});
        coarse.set(coarse.size() - 1, new int[]{to.getX(), to.getZ()});

        List<double[]> fine = resample(coarse);
        List<Integer> rawHeights = new ArrayList<>(fine.size());
        for (double[] point : fine) {
            // getBaseHeight returns the same "first open-air block above ground" convention as
            // ServerLevel#getHeight(WORLD_SURFACE, ...) below - subtract 1 up front so every height
            // here already means "the solid block a road surface actually replaces", never
            // recomputed or mixed with the raw convention past this point.
            rawHeights.add(generator.getBaseHeight((int) Math.round(point[0]), (int) Math.round(point[1]),
                    Heightmap.Types.WORLD_SURFACE_WG, overworld, randomState) - 1);
        }
        List<Integer> smoothed = clampSlope(rawHeights, fine, ctx.slopeRise(), ctx.slopeRun());

        // fine/smoothed are spaced FINE_STEP (4) blocks apart - cheap for terrain sampling and
        // slope-clamping, but painting a cross-section ONLY at those points would leave literal
        // gaps of unpaved terrain between each strip. Densify to ~1-block spacing purely for the
        // paint pass, interpolating the already-smoothed profile rather than resampling terrain.
        List<double[]> paint = densifyForPaint(fine, smoothed);

        // Rounding a path-aligned sample to the nearest world block does NOT produce a solid,
        // connected region on a diagonal heading - it produces a "staircase" of corner-touching
        // blocks with real gaps at the axis-aligned cells in between, no matter how dense the
        // sampling is (the sampling grid itself is rotated relative to the world's block grid).
        //
        // Inner/outer is decided by LANE RANK (offset from centerline), not by a cell's raw
        // distance to the continuous centerline - a per-distance threshold looked right on paper
        // but silently broke for almost every real heading: even an ideal 45-degree road only
        // touches distance 0 at the lattice points it happens to pass through, and the very next
        // cell over already sits at 1/sqrt(2) =~ 0.707 from that line - past a width-3 road's 0.5
        // inner/outer cutoff, so nearly everything read as "outer" instead of "inner". Lane rank
        // sidesteps this entirely: offset 0 is always inner, the two extreme offsets are always
        // outer, regardless of what angle the road happens to run at.
        //
        // Each lane still needs its own gap-free capsule along the travel direction (same reason as
        // above), sized just past sqrt(2)/2 - the worst-case distance from a lattice cell to a line
        // between two points one block apart - so no heading can produce an along-lane gap. Lanes
        // are only 1 block apart, so this can occasionally let one lane's capsule claim a cell that
        // "belongs" to its neighbor; processing lanes innermost-first (see laneOffsetsCenterOut) is
        // a deliberate tie-break so any such bleed favors keeping the inner core solid rather than
        // letting the outer border eat into it.
        int half = (ctx.width() - 1) / 2;
        int minOffset = -half;
        int maxOffset = ctx.width() - 1 - half;

        List<double[][]> segments = new ArrayList<>();
        if (paint.size() == 1) {
            double[] p = paint.get(0);
            segments.add(new double[][]{p, p});
        } else {
            for (int i = 0; i < paint.size() - 1; i++) {
                segments.add(new double[][]{paint.get(i), paint.get(i + 1)});
            }
        }

        Set<Long> painted = new HashSet<>();
        for (int offset : laneOffsetsCenterOut(minOffset, maxOffset)) {
            boolean isOuterEdge = offset == minOffset || offset == maxOffset;
            // Support piers drop from the two outer edge lanes (not the centerline - a pair of
            // edge piers reads far more like a real bridge than one dead-center pillar), at most
            // every pierInterval segments (each segment is ~1 block long, so this is ~1 block per
            // unit of interval) - see paveColumn for why every other lane, and every other position
            // along these two, gets no support at all, just a bare deck.
            boolean isPierLane = isOuterEdge;
            for (int i = 0; i < segments.size(); i++) {
                double[][] segment = segments.get(i);
                double[] a = segment[0];
                double[] b = segment[1];
                double[] perp = perpendicular(a, b);
                double[] laneA = {a[0] + perp[0] * offset, a[1] + perp[1] * offset, a[2]};
                double[] laneB = {b[0] + perp[0] * offset, b[1] + perp[1] * offset, b[2]};
                boolean allowPier = isPierLane && (i % ctx.pierInterval() == 0);
                paveLaneCapsule(overworld, laneA, laneB, ctx, forcedChunks, painted, isOuterEdge, allowPier);
            }
        }

        PathLightBuilder.placeAlongRoad(overworld, paint, minOffset, maxOffset, forcedChunks);

        return true;
    }

    // Offsets from the road's own centerline, ordered innermost-first (0 before +-1 before +-2, ...)
    // - see buildRoad for why processing order matters for the rare cell a lane capsule shares with
    // its neighbor.
    private static int[] laneOffsetsCenterOut(int minOffset, int maxOffset) {
        List<Integer> offsets = new ArrayList<>();
        for (int o = minOffset; o <= maxOffset; o++) offsets.add(o);
        offsets.sort((p, q) -> Integer.compare(Math.abs(p), Math.abs(q)));
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
        return result;
    }

    // Unit vector perpendicular to a->b, in the XZ plane - (0, 1) for a degenerate (zero-length)
    // segment, an arbitrary but consistent fallback for the size-1 "paint" edge case. Package-visible
    // so PathLightBuilder can place lights relative to the same lane geometry roads themselves use.
    static double[] perpendicular(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double len = Math.hypot(dx, dz);
        if (len < 1.0e-6) return new double[]{0, 1};
        return new double[]{-dz / len, dx / len};
    }

    // Linearly interpolates both position and the already-smoothed height between each consecutive
    // pair of (coarser) fine/smoothed points, at approximately 1-block spacing - see buildRoad for
    // why painting needs this and terrain sampling doesn't.
    private static List<double[]> densifyForPaint(List<double[]> fine, List<Integer> smoothed) {
        List<double[]> dense = new ArrayList<>();
        for (int i = 0; i < fine.size() - 1; i++) {
            double[] a = fine.get(i);
            double[] b = fine.get(i + 1);
            int ya = smoothed.get(i);
            int yb = smoothed.get(i + 1);
            double dist = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.round(dist));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                dense.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, ya + (yb - ya) * t});
            }
        }
        double[] lastFine = fine.get(fine.size() - 1);
        dense.add(new double[]{lastFine[0], lastFine[1], smoothed.get(smoothed.size() - 1)});
        return dense;
    }

    // Just past sqrt(2)/2 (~0.7071) - the worst-case distance from an integer lattice cell to a
    // line between two points exactly 1 block apart (a 45-degree heading, the case that motivated
    // this whole design - see buildRoad). Large enough that no heading can leave an along-lane gap;
    // small enough to mostly stay out of a neighboring lane's own territory, since lanes are
    // exactly 1 block apart.
    private static final double LANE_CAPSULE_RADIUS = 0.76;

    // Paints every grid cell within LANE_CAPSULE_RADIUS of the lane segment a-b (clamped to the
    // segment, so consecutive segments' capsules blend at their shared endpoint) - a cell already
    // claimed (by this lane's own earlier segment, or by an inner lane processed first - see
    // laneOffsetsCenterOut) is skipped, both for correctness (a column's material/terrain decision
    // is made once) and to avoid redundant work. isOuterEdge is fixed for the whole lane - it's a
    // property of which offset this lane is, not of any individual cell's own distance.
    private static void paveLaneCapsule(ServerLevel overworld, double[] a, double[] b, RoadContext ctx,
                                          Set<Long> forcedChunks, Set<Long> painted, boolean isOuterEdge, boolean allowPier) {
        double abx = b[0] - a[0];
        double abz = b[1] - a[1];
        double abLenSq = abx * abx + abz * abz;

        int x0 = (int) Math.floor(Math.min(a[0], b[0]) - LANE_CAPSULE_RADIUS - 1);
        int x1 = (int) Math.ceil(Math.max(a[0], b[0]) + LANE_CAPSULE_RADIUS + 1);
        int z0 = (int) Math.floor(Math.min(a[1], b[1]) - LANE_CAPSULE_RADIUS - 1);
        int z1 = (int) Math.ceil(Math.max(a[1], b[1]) + LANE_CAPSULE_RADIUS + 1);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                long key = pack(x, z);
                if (painted.contains(key)) continue;

                double t = abLenSq > 1.0e-9 ? ((x - a[0]) * abx + (z - a[1]) * abz) / abLenSq : 0.0;
                double tc = Math.max(0.0, Math.min(1.0, t));
                double closestX = a[0] + tc * abx;
                double closestZ = a[1] + tc * abz;
                double dist = Math.hypot(x - closestX, z - closestZ);
                if (dist > LANE_CAPSULE_RADIUS) continue;

                painted.add(key);
                int roadY = (int) Math.round(a[2] + tc * (b[2] - a[2]));
                paveColumn(overworld, x, z, roadY, ctx, forcedChunks, isOuterEdge, allowPier);
            }
        }
    }

    private static void paveColumn(ServerLevel overworld, int x, int z, int roadY, RoadContext ctx,
                                     Set<Long> forcedChunks, boolean isOuterEdge, boolean allowPier) {
        long chunkKey = pack(x >> 4, z >> 4);
        if (forcedChunks.add(chunkKey)) {
            overworld.getChunk(x >> 4, z >> 4);
        }

        // Same "-1" conversion as rawHeights above, so terrainY and roadY are directly comparable
        // as the same "solid block the road surface sits at" convention - without it, every road
        // would sit one block above the natural ground even on perfectly flat terrain.
        //
        // OCEAN_FLOOR, not WORLD_SURFACE - WORLD_SURFACE counts a water/lava surface as "terrain",
        // so over open water this used to report the waterline itself rather than the real lakebed
        // far below. That made the "gap" fill above only ever bridge the thin sliver between the
        // road and the water's surface, leaving the water (and whatever's under it) completely
        // unsupported - a bridge that looked solid right at the waterline but had nothing actually
        // holding it up. OCEAN_FLOOR ignores fluids and reports the real solid ground underneath, so
        // the fill below now correctly recognizes the true depth of the gap and fills all the way
        // down to it; on dry land, with no fluid involved, this is identical to WORLD_SURFACE.
        int terrainY = overworld.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        if (roadY >= terrainY) {
            // Bridging a gap - the deck itself (placed unconditionally below, same as any other
            // column) is always solid and continuous, so the pathway is always fully walkable. The
            // support underneath it isn't: only the two outer edge lanes, only every pierInterval
            // blocks, drop an actual pier down to the real ground - everywhere else (including the
            // inner lanes at those same positions) is a bare floating deck, like a real beam
            // bridge's periodic edge piers rather than a solid wall filling the entire gap.
            if (allowPier) {
                placePier(overworld, x, roadY - 1, z, ctx.bridge(), ctx.pierMaxHeight());
            }
        } else {
            for (int y = roadY + 1; y <= terrainY; y++) {
                overworld.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }

        overworld.setBlock(cursor.set(x, roadY, z), isOuterEdge ? ctx.outer() : ctx.inner(), 2);

        // RoadWeaver firms up mud directly under a road, since a path visually sitting on top of
        // soft mud reads oddly - one extra check, same idea, our own block constants.
        BlockPos underPos = cursor.set(x, roadY - 1, z);
        if (overworld.getBlockState(underPos).is(Blocks.MUD)) {
            overworld.setBlock(underPos, Blocks.PACKED_MUD.defaultBlockState(), 2);
        }

        for (int y = roadY + 1; y <= roadY + HEADROOM; y++) {
            overworld.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    // Drops a single-column pier straight down from just under the deck, stopping the moment it
    // hits a sturdy-up-facing block (real ground - the pier only needs to reach it, not bury into
    // it) or after pierMaxHeight blocks, whichever comes first - a safety cap so a pier over a
    // genuinely deep gap doesn't descend forever.
    private static void placePier(ServerLevel overworld, int x, int fromY, int z, BlockState pierMaterial, int maxHeight) {
        int minY = Math.max(overworld.getMinBuildHeight(), fromY - maxHeight);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = fromY; y >= minY; y--) {
            cursor.set(x, y, z);
            if (overworld.getBlockState(cursor).isFaceSturdy(overworld, cursor, Direction.UP)) break;
            overworld.setBlock(cursor, pierMaterial, 2);
        }
    }

    private static List<double[]> resample(List<int[]> coarse) {
        List<double[]> fine = new ArrayList<>();
        for (int i = 0; i < coarse.size() - 1; i++) {
            int[] a = coarse.get(i);
            int[] b = coarse.get(i + 1);
            double dist = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.round(dist / FINE_STEP));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                fine.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
            }
        }
        int[] last = coarse.get(coarse.size() - 1);
        fine.add(new double[]{last[0], last[1]});
        return fine;
    }

    // Two-pass (forward, then backward) max-slope clamp, porting RoadWeaver's
    // HighwayHeightSmoother - a real physical constraint (no step may exceed slopeRise/slopeRun
    // per block traveled) rather than cosmetic averaging, so a locally-steep stretch can't survive
    // inside a smoothing window the way a moving average would let it.
    //
    // Deliberately never clamps index 0 or the last index - those are the exact anchor heights
    // FrontAnchor placed the road against, so a road always meets its structure flush no matter how
    // steep the terrain between the two ends is; only the interior is allowed to bend to satisfy the
    // slope constraint. RoadWeaver's own version doesn't need this distinction since its smoothing
    // runs are bounded by bridge markers, not by two hard, externally-fixed endpoints.
    private static List<Integer> clampSlope(List<Integer> raw, List<double[]> fine, int riseBlocks, int runBlocks) {
        int n = raw.size();
        if (n <= 2 || riseBlocks <= 0) return new ArrayList<>(raw);

        double maxSlope = (double) riseBlocks / Math.max(1, runBlocks);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = raw.get(i);

        for (int k = 1; k < n - 1; k++) {
            double maxDelta = maxSlope * dist2d(fine.get(k - 1), fine.get(k));
            y[k] = clampRange(y[k], y[k - 1] - maxDelta, y[k - 1] + maxDelta);
        }
        for (int k = n - 2; k >= 1; k--) {
            double maxDelta = maxSlope * dist2d(fine.get(k), fine.get(k + 1));
            y[k] = clampRange(y[k], y[k + 1] - maxDelta, y[k + 1] + maxDelta);
        }

        List<Integer> out = new ArrayList<>(n);
        out.add(raw.get(0));
        for (int k = 1; k < n; k++) {
            double v = (k == n - 1) ? raw.get(n - 1) : y[k];
            int prev = out.get(k - 1);
            // Directional rounding (floor when rising, ceil when falling) so integer rounding never
            // sneaks a step past the clamp we just computed in double precision.
            out.add(v >= prev ? (int) Math.floor(v + 1.0e-9) : (int) Math.ceil(v - 1.0e-9));
        }
        return out;
    }

    private static double dist2d(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static double clampRange(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // Coarse-grid A* over COARSE_STEP-block cells - null if no route was found within the search
    // budget. Height is sampled with ChunkGenerator#getBaseHeight, so this never forces a chunk to
    // generate; a blocked-box column is simply never expanded into, treated as an impassable wall.
    private static List<int[]> aStar(ChunkGenerator generator, RandomState randomState, ServerLevel overworld,
                                       int fromX, int fromZ, int toX, int toZ, List<BoundingBox> blockedBoxes) {
        int startGx = Math.floorDiv(fromX, COARSE_STEP);
        int startGz = Math.floorDiv(fromZ, COARSE_STEP);
        int goalGx = Math.floorDiv(toX, COARSE_STEP);
        int goalGz = Math.floorDiv(toZ, COARSE_STEP);

        Map<Long, Integer> heightCache = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        // Each entry is {fScore, gx, gz} - packed as doubles so a single PriorityQueue can order by
        // fScore without a wrapper object per node.
        PriorityQueue<double[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));

        long startKey = pack(startGx, startGz);
        gScore.put(startKey, 0.0);
        frontier.add(new double[]{heuristic(startGx, startGz, goalGx, goalGz), startGx, startGz});

        Set<Long> closed = new HashSet<>();
        int expansions = 0;

        while (!frontier.isEmpty()) {
            if (expansions++ > MAX_NODE_EXPANSIONS) return null;

            double[] current = frontier.poll();
            int gx = (int) current[1];
            int gz = (int) current[2];
            long key = pack(gx, gz);
            if (!closed.add(key)) continue;

            if (gx == goalGx && gz == goalGz) {
                return reconstruct(cameFrom, key);
            }

            double currentG = gScore.getOrDefault(key, Double.MAX_VALUE);
            int currentHeight = heightAt(generator, randomState, overworld, gx, gz, heightCache);

            for (int[] delta : NEIGHBOR_DELTAS) {
                int ngx = gx + delta[0];
                int ngz = gz + delta[1];
                long nKey = pack(ngx, ngz);
                if (closed.contains(nKey)) continue;

                // The start and goal cells sit deliberately close to their own structure (that's
                // the whole point of a FrontAnchor) - never let that structure's own bounding box
                // (expanded by BLOCKED_BOX_MARGIN) block the very endpoints the road has to reach.
                boolean isEndpoint = (ngx == startGx && ngz == startGz) || (ngx == goalGx && ngz == goalGz);
                if (!isEndpoint) {
                    int nx = ngx * COARSE_STEP;
                    int nz = ngz * COARSE_STEP;
                    if (isBlocked(nx, nz, blockedBoxes)) continue;
                }

                double stepDist = Math.hypot(delta[0], delta[1]) * COARSE_STEP;
                int neighborHeight = heightAt(generator, randomState, overworld, ngx, ngz, heightCache);
                double cost = stepDist + ELEVATION_WEIGHT * Math.abs(neighborHeight - currentHeight);
                double tentativeG = currentG + cost;

                if (tentativeG < gScore.getOrDefault(nKey, Double.MAX_VALUE)) {
                    gScore.put(nKey, tentativeG);
                    cameFrom.put(nKey, key);
                    frontier.add(new double[]{tentativeG + heuristic(ngx, ngz, goalGx, goalGz), ngx, ngz});
                }
            }
        }

        return null;
    }

    private static final int[][] NEIGHBOR_DELTAS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static int heightAt(ChunkGenerator generator, RandomState randomState, ServerLevel overworld,
                                  int gx, int gz, Map<Long, Integer> cache) {
        long key = pack(gx, gz);
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        int height = generator.getBaseHeight(gx * COARSE_STEP, gz * COARSE_STEP, Heightmap.Types.WORLD_SURFACE_WG, overworld, randomState);
        cache.put(key, height);
        return height;
    }

    private static boolean isBlocked(int x, int z, List<BoundingBox> blockedBoxes) {
        for (BoundingBox box : blockedBoxes) {
            if (x >= box.minX() - BLOCKED_BOX_MARGIN && x <= box.maxX() + BLOCKED_BOX_MARGIN
                    && z >= box.minZ() - BLOCKED_BOX_MARGIN && z <= box.maxZ() + BLOCKED_BOX_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static double heuristic(int gx, int gz, int goalGx, int goalGz) {
        return Math.hypot(gx - goalGx, gz - goalGz) * COARSE_STEP;
    }

    private static List<int[]> reconstruct(Map<Long, Long> cameFrom, long goalKey) {
        List<int[]> path = new ArrayList<>();
        Long current = goalKey;
        while (current != null) {
            path.add(new int[]{unpackX(current) * COARSE_STEP, unpackZ(current) * COARSE_STEP});
            current = cameFrom.get(current);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    // Generic (x, z) bit-packing utility - used both for the A* grid (cell coordinates) and for
    // painted/forced-chunk column tracking (block/chunk coordinates); it's a pure packing function,
    // so any int domain is safe to reuse it for.
    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static BlockState resolveBlock(String registryName, Block fallback) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(registryName));
        if (block == null) {
            LOGGER.error("Unknown road block \"{}\" - falling back to {}.", registryName, fallback);
            return fallback.defaultBlockState();
        }
        return block.defaultBlockState();
    }
}
