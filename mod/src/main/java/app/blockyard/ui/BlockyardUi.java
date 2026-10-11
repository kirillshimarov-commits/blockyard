package app.blockyard.ui;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared state and the settings the launcher writes to config/blockyard-ui.json. */
public final class BlockyardUi {
    /** Accent color as 0xRRGGBB. */
    public static int accent = 0x8B5CFF;
    /** Set to open the vanilla title screen once (the "classic menu" item). */
    public static boolean vanillaOnce = false;

    private static boolean loaded = false;

    private BlockyardUi() {}

    public static void loadConfig() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("blockyard-ui.json");
            if (!Files.isRegularFile(file)) return;
            Matcher m = Pattern.compile("\"accent\"\\s*:\\s*\"#?([0-9a-fA-F]{6})\"").matcher(Files.readString(file));
            if (m.find()) accent = Integer.parseInt(m.group(1), 16);
        } catch (Exception ignored) {
            // Defaults are fine.
        }
    }
}
