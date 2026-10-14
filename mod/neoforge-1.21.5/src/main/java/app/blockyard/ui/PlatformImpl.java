package app.blockyard.ui;

import app.blockyard.ui.core.HomeView;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What the shared code needs from the mod loader, on NeoForge. */
public final class PlatformImpl {
    /** Parts of the game itself rather than mods the player installed. */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "neoforge");

    private PlatformImpl() {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Installed mods without icons, by name. */
    public static List<HomeView.Mod> mods() {
        List<HomeView.Mod> out = new ArrayList<>();
        ModList.get().getMods().stream()
                .filter(m -> !BUILT_IN.contains(m.getModId()))
                .sorted(Comparator.comparing((IModInfo m) -> m.getDisplayName().toLowerCase(Locale.ROOT)))
                .forEach(m -> out.add(new HomeView.Mod(m.getModId(), m.getDisplayName(), m.getVersion().toString(), m.getDescription().trim(), null)));
        return out;
    }

    /** The mod's logo file, if it has one. */
    public static Path modIcon(String id) {
        return ModList.get().getModContainerById(id).flatMap(c -> {
            IModInfo info = c.getModInfo();
            return info.getLogoFile().map(logo -> info.getOwningFile().getFile().findResource(logo));
        }).filter(Files::isRegularFile).orElse(null);
    }
}
