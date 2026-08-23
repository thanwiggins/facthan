package com.thanwiggins.facthan;

// Thrown by CapitalRealmPlanner when the capital search exhausts its retries on this world seed.
// Per the "no exceptions" force-generation guarantee, we refuse to let the world finish creating
// in a half-decided state - this propagates out of MinecraftServerMixin's injection into
// MinecraftServer#prepareLevels, up through the normal server-startup call stack. In singleplayer,
// KingdomWorldRetry/MinecraftCrashRedirectMixin/WorldOpenFlowsRetryMixin intercept it before it
// would otherwise hard-crash the game (see Minecraft#crash) and silently recreate the world with a
// freshly-rolled seed instead, up to a bounded number of attempts; on a dedicated server (no client
// to intercept anything) it's a startup failure logged plainly to the server console.
public class KingdomGenerationAbortedException extends RuntimeException {
    public KingdomGenerationAbortedException(String message) {
        super(message);
    }
}
