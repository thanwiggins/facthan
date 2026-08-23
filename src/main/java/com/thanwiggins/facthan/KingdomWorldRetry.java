package com.thanwiggins.facthan;

import com.mojang.logging.LogUtils;
import net.minecraft.CrashReport;
import org.slf4j.Logger;

// The "flush the world seed and start over" half of the design, done properly instead of letting
// it fall through to Minecraft's own integrated-server crash handling - which, for a failure
// during world creation specifically, doesn't show a friendly "Back to Title" screen at all, it
// hard-exits the whole game via System.exit() (see Minecraft#crash). MinecraftClientMixin redirects
// that call for our specific exception; WorldOpenFlowsMixin (see its own class) then recurses
// createFreshLevel with a freshly-rolled seed - fully silent to the player, bounded here so a
// truly impossible config (e.g. minDistanceBetweenCapitals bigger than the world border allows)
// still eventually surfaces as a real error instead of retrying forever.
public final class KingdomWorldRetry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ATTEMPTS = 5;

    private static volatile boolean pending = false;
    private static int attempts = 0;

    private KingdomWorldRetry() {}

    // Called in place of Minecraft.crash(report) inside Minecraft#doWorldLoad. Returns true if the
    // crash should be swallowed (so doWorldLoad's own following `return;` just exits normally)
    // because this is our exception and we haven't exceeded the retry budget yet.
    public static boolean shouldSwallow(CrashReport report) {
        if (!(report.getException() instanceof KingdomGenerationAbortedException)) return false;

        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            LOGGER.error("Gave up after {} seed-flush attempts on this world - letting it fail normally.", MAX_ATTEMPTS);
            attempts = 0;
            return false;
        }

        LOGGER.warn("Capital search failed on this seed (attempt {}/{}) - flushing and trying a new seed.", attempts, MAX_ATTEMPTS);
        pending = true;
        return true;
    }

    // Called at the tail of WorldOpenFlows#createFreshLevel. Returns true (and consumes the flag)
    // if that call needs to recurse with a new seed; resets the attempt counter otherwise, since a
    // call that didn't need a retry means the previous chain (if any) is over, one way or another.
    public static boolean consumeRetry() {
        if (!pending) {
            attempts = 0;
            return false;
        }
        pending = false;
        return true;
    }
}
