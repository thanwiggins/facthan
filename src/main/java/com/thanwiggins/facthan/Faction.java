package com.thanwiggins.facthan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

// A political faction loaded from a data/<namespace>/political_factions/<path>.json file - the
// file's own resource location IS the faction's id (see FactionRegistry), so any number of
// datapacks can each contribute factions without needing a central registry list.
//
// Registering a faction here only makes it eligible to be assigned Voronoi cells (territory) - a
// structure_set actually belongs to one by using the "mcaichat:faction_spread" placement type
// (FactionStructurePlacement) with a matching "faction" field, not by anything in this file.
public record Faction(String displayName, String colorHex) {
    public static final Codec<Faction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("display_name").forGetter(Faction::displayName),
            Codec.STRING.fieldOf("color").forGetter(Faction::colorHex)
    ).apply(instance, Faction::new));

    // ARGB, matching the "#RRGGBB" convention used in the JSON.
    public int color() {
        String hex = colorHex.startsWith("#") ? colorHex.substring(1) : colorHex;
        try {
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }
}
