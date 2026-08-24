package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

// Debug-only: logs every entity that joins the world while CapitalRealmPlanner is actively running
// (KingdomBootStatus is non-null only during that window - cleared the moment it finishes, well
// before normal spawn-chunk generation or gameplay resumes), so a duplicated-entity report can be
// matched against exactly when/where each spawn happened instead of just the end result. Look for
// this in the log as "[Kingdom gen] Entity joined: ...".
@Mod.EventBusSubscriber(modid = FacthanMod.MODID)
public class KingdomEntitySpawnLogger {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        String step = KingdomBootStatus.get();
        if (step == null) return;

        LOGGER.info("[Kingdom gen] Entity joined: {} at {} (loadedFromDisk={}) - during: {}",
                event.getEntity().getType(), event.getEntity().blockPosition(), event.loadedFromDisk(), step);
    }
}
