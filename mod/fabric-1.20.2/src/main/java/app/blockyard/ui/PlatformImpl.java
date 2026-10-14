package app.blockyard.ui;

import app.blockyard.ui.core.HomeView;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** What the shared code needs from the mod loader, on Fabric (and Quilt). */
public final class PlatformImpl {
    private PlatformImpl() {}

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** Installed mods without icons, by name. */
    public static List<HomeView.Mod> mods() {
        List<HomeView.Mod> out = new ArrayList<>();
        FabricLoader.getInstance().getAllMods().stream()
                .filter(m -> !m.getMetadata().getType().equals("builtin") && m.getContainingMod().isEmpty())
                .sorted(Comparator.comparing(m -> m.getMetadata().getName().toLowerCase(Locale.ROOT)))
                .forEach(m -> {
                    ModMetadata meta = m.getMetadata();
                    out.add(new HomeView.Mod(meta.getId(), meta.getName(), meta.getVersion().getFriendlyString(), meta.getDescription(), null));
                });
        return out;
    }

    /** The mod's icon file, if it has one. */
    public static Path modIcon(String id) {
        ModContainer c = FabricLoader.getInstance().getModContainer(id).orElse(null);
        if (c == null) return null;
        String path = c.getMetadata().getIconPath(64).orElse(null);
        return path == null ? null : c.findPath(path).orElse(null);
    }
}
