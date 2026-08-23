package com.thanwiggins.facthan;

// CapitalRealmPlanner runs on the (integrated or dedicated) server thread, blocking that thread
// before any chunk generates. In singleplayer this happens while the client is sitting on the
// vanilla "Building world" LevelLoadingScreen, polling on a completely different thread - this is
// the cross-thread channel that lets a client-side overlay (see the mixin/client package) show
// what step the search is currently on. `currentStep` is only ever read for display; nothing
// about world generation depends on it.
public final class KingdomBootStatus {
    private static volatile String currentStep = null;

    private KingdomBootStatus() {}

    public static void set(String step) {
        currentStep = step;
    }

    public static void clear() {
        currentStep = null;
    }

    public static String get() {
        return currentStep;
    }
}
