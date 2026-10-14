package app.blockyard.ui;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** NeoForge needs a mod class; everything else happens in the mixins. */
@Mod(value = "blockyard_ui", dist = Dist.CLIENT)
public final class NeoForgeEntry {
    public NeoForgeEntry() {}
}
