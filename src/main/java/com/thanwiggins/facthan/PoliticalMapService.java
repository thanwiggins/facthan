package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

// The single entry point for asking "what faction, if any, owns this position" - ties the pure
// PoliticalMap geometry together with the datapack-loaded FactionRegistry and the server's own
// PoliticalConfig knobs. This is both the "queryable internal API" the design calls for (see
// PoliticalMapCommand) and the exact check FactionStructurePlacement.isPlacementChunk delegates to.
public final class PoliticalMapService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PoliticalMapService() {}

    public record Ownership(ResourceLocation factionId, Faction faction, double distanceToBorder, boolean inBufferZone) {}

    // Empty only when no factions are configured at all - a valid, common state (the feature is
    // opt-in), not an error.
    public static Optional<Ownership> ownerAt(long worldSeed, int blockX, int blockZ) {
        List<ResourceLocation> orderedFactions = FactionRegistry.orderedFactionIds();
        if (orderedFactions.isEmpty()) return Optional.empty();

        int cellSize = PoliticalConfig.CELL_SIZE.get();
        PoliticalMap.CellResult cell = PoliticalMap.lookupCell(worldSeed, cellSize, blockX, blockZ);

        long mixed = PoliticalMap.mixCellAndSeed(worldSeed, cell.cellId());
        int factionIndex = (int) Long.remainderUnsigned(mixed, orderedFactions.size());
        ResourceLocation factionId = orderedFactions.get(factionIndex);
        Faction faction = FactionRegistry.factions().get(factionId);

        double distanceToBorder = cell.approxDistanceToBorder();
        boolean inBufferZone = distanceToBorder < PoliticalConfig.BORDER_BUFFER_WIDTH.get();

        return Optional.of(new Ownership(factionId, faction, distanceToBorder, inBufferZone));
    }

    // Called from FactionStructurePlacement.isPlacementChunk once vanilla's own spacing/separation
    // grid has already said yes - requiredFaction is whatever that structure_set's placement JSON
    // declared, not looked up from anywhere else.
    public static boolean isAllowedAt(long worldSeed, ResourceLocation requiredFaction, int blockX, int blockZ) {
        if (!PoliticalConfig.ENABLED.get()) return true;

        if (!FactionRegistry.factions().containsKey(requiredFaction)) {
            LOGGER.warn("Structure set references unregistered faction {} - it will never generate. " +
                    "Add a data/.../political_factions/ file for it.", requiredFaction);
            return false;
        }

        Optional<Ownership> ownership = ownerAt(worldSeed, blockX, blockZ);
        if (ownership.isEmpty()) return true;

        Ownership owned = ownership.get();
        return !owned.inBufferZone() && owned.factionId().equals(requiredFaction);
    }
}
