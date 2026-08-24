package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

// Deletes any Xaero's Minimap/World Map cache folder sitting in this world's save directory,
// called once from CapitalRealmPlanner right after it finishes force-generating every capital and
// realm. That timing is the whole point: this runs during MinecraftServer#prepareLevels, before
// the client has ever joined the freshly created world, so Xaero's mods (which, in singleplayer,
// cache map data straight from the world save on disk - not only chunks the player has actually
// walked into render distance of) haven't had any chance yet to read the just-force-generated
// capitals/realms. Matched by folder name rather than one hardcoded path since Xaero's Minimap and
// World Map keep separate caches and neither mod documents its exact on-disk layout.
public final class XaeroCacheFlush {
    private static final Logger LOGGER = LogUtils.getLogger();

    private XaeroCacheFlush() {}

    public static void flush(MinecraftServer server) {
        if (!KingdomConfig.FLUSH_XAERO_MAP_CACHE.get()) return;

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        try (Stream<Path> children = Files.list(worldRoot)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("xaero"))
                    .forEach(XaeroCacheFlush::deleteRecursively);
        } catch (IOException e) {
            LOGGER.warn("Couldn't scan the world save for a Xaero map cache to flush.", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOGGER.warn("Couldn't delete {} while flushing the Xaero map cache.", path, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Couldn't walk Xaero map cache folder {}.", dir, e);
            return;
        }
        LOGGER.info("Flushed Xaero map cache folder {} so it won't reveal force-generated capitals/realms before they're explored.", dir);
    }
}
