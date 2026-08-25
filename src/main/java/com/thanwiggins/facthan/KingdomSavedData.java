package com.thanwiggins.facthan;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Persists the one-time outcome of CapitalRealmPlanner's capital search + realm building - a
// deliberate departure from the rest of the mod's "pure function of the seed, nothing persisted"
// design (see the comment atop the now-retired PoliticalMap.java), since a capital's exact
// location has to survive server restarts once it's been decided. "finalized" is the guard that
// stops the whole routine from ever re-running on a world it has already completed.
public class KingdomSavedData extends SavedData {
    private static final String ID = "facthan_kingdom";

    // A server process only ever has one loaded world at a time, so a simple static cache -
    // refreshed whenever this data is loaded or changes - is enough to let
    // FactionStructurePlacement.isPlacementChunk answer "is my faction finalized" without needing
    // a ServerLevel reference of its own (ChunkGeneratorStructureState doesn't carry one).
    private static volatile Set<ResourceLocation> finalizedFactionsCache = Set.of();

    private final Map<ResourceLocation, BlockPos> capitals = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<BlockPos>> supportingStructures = new HashMap<>();
    private boolean finalized = false;

    public static KingdomSavedData get(ServerLevel overworld) {
        KingdomSavedData data = overworld.getDataStorage().computeIfAbsent(KingdomSavedData::load, KingdomSavedData::new, ID);
        data.refreshCache();
        return data;
    }

    public static boolean isFinalizedFactionCached(ResourceLocation faction) {
        return finalizedFactionsCache.contains(faction);
    }

    // Activates the placement veto for these factions immediately, well before markFinalized()
    // persists anything - called the moment the full capital set has validated (see
    // CapitalRealmPlanner), strictly before any force-generation begins, so this mod's own forced
    // chunk-loading during that force-generation can't still trigger one of these factions' other,
    // otherwise-eligible structure_sets via their ordinary spacing grid.
    public static void reserveFactions(Set<ResourceLocation> factions) {
        finalizedFactionsCache = Set.copyOf(factions);
    }

    private void refreshCache() {
        finalizedFactionsCache = finalized ? Set.copyOf(capitals.keySet()) : Set.of();
    }

    public boolean isFinalized() {
        return finalized;
    }

    public void markFinalized() {
        this.finalized = true;
        refreshCache();
        setDirty();
    }

    public Map<ResourceLocation, BlockPos> capitals() {
        return capitals;
    }

    public void putCapital(ResourceLocation faction, BlockPos pos) {
        capitals.put(faction, pos);
        setDirty();
    }

    // Covers a faction's supporting structures regardless of which routine placed them - a
    // capital-faction's realm (CapitalRealmPlanner#validateRealm) or an orphan faction's one-time
    // structures (CapitalRealmPlanner#validateOrphans) are recorded identically here.
    public List<BlockPos> supportingStructures(ResourceLocation faction) {
        return supportingStructures.computeIfAbsent(faction, f -> new ArrayList<>());
    }

    public void addSupportingStructure(ResourceLocation faction, BlockPos pos) {
        supportingStructures(faction).add(pos);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Finalized", finalized);

        ListTag capitalsTag = new ListTag();
        capitals.forEach((faction, pos) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Faction", faction.toString());
            entry.put("Pos", writeBlockPos(pos));
            capitalsTag.add(entry);
        });
        tag.put("Capitals", capitalsTag);

        ListTag realmsTag = new ListTag();
        supportingStructures.forEach((faction, positions) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Faction", faction.toString());
            ListTag posList = new ListTag();
            positions.forEach(pos -> posList.add(writeBlockPos(pos)));
            entry.put("Structures", posList);
            realmsTag.add(entry);
        });
        tag.put("Realms", realmsTag);

        return tag;
    }

    public static KingdomSavedData load(CompoundTag tag) {
        KingdomSavedData data = new KingdomSavedData();
        data.finalized = tag.getBoolean("Finalized");

        for (Tag t : tag.getList("Capitals", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            ResourceLocation faction = new ResourceLocation(entry.getString("Faction"));
            data.capitals.put(faction, readBlockPos(entry.getCompound("Pos")));
        }

        for (Tag t : tag.getList("Realms", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            ResourceLocation faction = new ResourceLocation(entry.getString("Faction"));
            List<BlockPos> positions = new ArrayList<>();
            for (Tag p : entry.getList("Structures", Tag.TAG_COMPOUND)) {
                positions.add(readBlockPos((CompoundTag) p));
            }
            data.supportingStructures.put(faction, positions);
        }

        return data;
    }

    private static CompoundTag writeBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    private static BlockPos readBlockPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }
}
