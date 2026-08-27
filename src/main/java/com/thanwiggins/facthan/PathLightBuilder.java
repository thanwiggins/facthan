package com.thanwiggins.facthan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Stamps a small decorative light (see /path_lights/lamp.json, authored from the user-supplied
// path_light_blueprint.JSON) along both outer edges of a road at roughly KingdomConfig.PATH_LIGHT_SPACING
// intervals - called once from RoadBuilder.buildRoad, right after that road's own lanes are painted, so
// it can reuse the exact same dense (~1-block-spaced) centerline points RoadBuilder already computed.
final class PathLightBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    // The blueprint's own center pole, in its native (0-2, 0-6, 0-2) coordinate system - every
    // block below is stored relative to this, at ground level.
    private static final int BLUEPRINT_CENTER_X = 1;
    private static final int BLUEPRINT_CENTER_Z = 1;

    // The blueprint is a plus-shape at every layer that isn't just the center pole (see
    // path_lights/lamp.json) - 1 block from its own center in every direction, never more.
    private static final int ARM_RADIUS = 1;
    private static final int STRUCTURE_HEIGHT = 6;

    // Empty columns left between a road's outer-edge lane and the light's nearest occupied block
    // (its arm) - "one block away from the outer edge", per the original request.
    private static final int GAP = 1;

    // Distance from a road's outer-edge lane to a light's own center pole: GAP empty columns, then
    // one more column to reach the light's nearest block (its arm), then ARM_RADIUS more to reach
    // the center the arm itself is offset from.
    private static final int CENTER_OFFSET = GAP + 1 + ARM_RADIUS;

    // A shoulder position is skipped (see placePair) if its natural terrain height differs from the
    // road's own surface height here by more than this - catches cliffs/steep drop-offs beside an
    // otherwise normal (non-bridge) stretch of road.
    private static final int MAX_HEIGHT_DELTA = 3;

    private record BlueprintBlock(int dx, int dy, int dz, BlockState state) {}

    private static final List<BlueprintBlock> BLUEPRINT = loadBlueprint();

    private PathLightBuilder() {}

    static void placeAlongRoad(ServerLevel overworld, List<double[]> paint, int minOffset, int maxOffset,
                                 Set<Long> forcedChunks) {
        if (!KingdomConfig.ENABLE_PATH_LIGHTS.get() || BLUEPRINT.isEmpty() || paint.size() < 2) return;

        int spacing = KingdomConfig.PATH_LIGHT_SPACING.get();
        double distanceSinceLast = 0;
        for (int i = 0; i < paint.size() - 1; i++) {
            double[] a = paint.get(i);
            double[] b = paint.get(i + 1);
            distanceSinceLast += Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (distanceSinceLast < spacing) continue;
            distanceSinceLast -= spacing;

            double[] perp = RoadBuilder.perpendicular(a, b);
            placePair(overworld, b, perp, minOffset, maxOffset, forcedChunks);
        }
    }

    // Places (up to) two lights straddling the road at centerline point "at" - one on each outer
    // edge - skipping either side individually if its shoulder isn't safe ground to stand a light
    // on (see MAX_HEIGHT_DELTA), or both entirely if the road itself is bridging a gap here.
    private static void placePair(ServerLevel overworld, double[] at, double[] perp, int minOffset,
                                    int maxOffset, Set<Long> forcedChunks) {
        int roadY = (int) Math.round(at[2]);
        int centerX = (int) Math.round(at[0]);
        int centerZ = (int) Math.round(at[1]);

        int centerlineTerrainY = groundY(overworld, centerX, centerZ, forcedChunks);
        if (roadY >= centerlineTerrainY) return; // the road itself is a floating bridge deck here

        placeSide(overworld, at, perp, maxOffset + CENTER_OFFSET, roadY, forcedChunks);
        placeSide(overworld, at, perp, minOffset - CENTER_OFFSET, roadY, forcedChunks);
    }

    private static void placeSide(ServerLevel overworld, double[] at, double[] perp, int offset, int roadY,
                                    Set<Long> forcedChunks) {
        int x = (int) Math.round(at[0] + perp[0] * offset);
        int z = (int) Math.round(at[1] + perp[1] * offset);

        int shoulderY = groundY(overworld, x, z, forcedChunks);
        if (Math.abs(shoulderY - roadY) > MAX_HEIGHT_DELTA) return;

        placeLight(overworld, x, shoulderY, z, forcedChunks);
    }

    // Stamps one light with its ground-level (dy=0) layer at baseY - the same "flush with, not
    // floating above, the natural surface" convention RoadBuilder itself uses for road height.
    private static void placeLight(ServerLevel overworld, int x, int baseY, int z, Set<Long> forcedChunks) {
        forceChunk(overworld, x, z, forcedChunks);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -ARM_RADIUS; dx <= ARM_RADIUS; dx++) {
            for (int dz = -ARM_RADIUS; dz <= ARM_RADIUS; dz++) {
                for (int y = baseY + 1; y <= baseY + STRUCTURE_HEIGHT + 1; y++) {
                    overworld.setBlock(cursor.set(x + dx, y, z + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }

        for (BlueprintBlock block : BLUEPRINT) {
            overworld.setBlock(cursor.set(x + block.dx(), baseY + block.dy(), z + block.dz()), block.state(), 2);
        }
    }

    private static int groundY(ServerLevel overworld, int x, int z, Set<Long> forcedChunks) {
        forceChunk(overworld, x, z, forcedChunks);
        return overworld.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
    }

    private static void forceChunk(ServerLevel overworld, int x, int z, Set<Long> forcedChunks) {
        long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
        if (forcedChunks.add(chunkKey)) {
            overworld.getChunk(x >> 4, z >> 4);
        }
    }

    private static List<BlueprintBlock> loadBlueprint() {
        try (InputStream in = PathLightBuilder.class.getResourceAsStream("/path_lights/lamp.json")) {
            if (in == null) {
                LOGGER.error("Missing path_lights/lamp.json - path lights will not be generated.");
                return List.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<BlueprintBlock> blocks = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("blocks")) {
                JsonObject obj = element.getAsJsonObject();
                Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(obj.get("block").getAsString()));
                if (block == null) {
                    LOGGER.error("Unknown block \"{}\" in path light blueprint - skipping that block.", obj.get("block").getAsString());
                    continue;
                }

                BlockState state = block.defaultBlockState();
                if (obj.has("properties")) {
                    for (Map.Entry<String, JsonElement> property : obj.getAsJsonObject("properties").entrySet()) {
                        state = applyProperty(state, property.getKey(), property.getValue().getAsString());
                    }
                }

                blocks.add(new BlueprintBlock(obj.get("x").getAsInt() - BLUEPRINT_CENTER_X, obj.get("y").getAsInt(),
                        obj.get("z").getAsInt() - BLUEPRINT_CENTER_Z, state));
            }
            return List.copyOf(blocks);
        } catch (Exception e) {
            LOGGER.error("Failed to load path_lights/lamp.json - path lights will not be generated.", e);
            return List.of();
        }
    }

    private static BlockState applyProperty(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            LOGGER.error("Unknown property \"{}\" on {} in path light blueprint.", name, state.getBlock());
            return state;
        }
        return setValue(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            LOGGER.error("Unknown value \"{}\" for property \"{}\" in path light blueprint.", value, property.getName());
            return state;
        }
        return state.setValue(property, parsed.get());
    }
}
