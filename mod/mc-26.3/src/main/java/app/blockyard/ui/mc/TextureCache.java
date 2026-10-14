package app.blockyard.ui.mc;

import app.blockyard.ui.core.Raster;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Rasterized UI pieces uploaded as textures (premultiplied alpha), reused while they are drawn
 * and freed a while after they stop being drawn.
 */
final class TextureCache {
    record Entry(Identifier id, int w, int h, float ox, float oy) {}

    private static final class Slot {
        Entry entry;
        long lastUsed;
    }

    private static final Map<String, Slot> SLOTS = new HashMap<>();
    private static long lastSweep = 0;
    private static int counter = 0;

    private TextureCache() {}

    /** Frees textures not drawn for 15 seconds (checked every 5 seconds). */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 5000) return;
        lastSweep = now;
        Iterator<Map.Entry<String, Slot>> it = SLOTS.entrySet().iterator();
        while (it.hasNext()) {
            Slot s = it.next().getValue();
            if (now - s.lastUsed > 15000) {
                Minecraft.getInstance().getTextureManager().release(s.entry.id());
                it.remove();
            }
        }
    }

    static Entry get(String key, boolean smooth, Supplier<Raster.Img> make) {
        Slot slot = SLOTS.get(key);
        if (slot == null) {
            slot = new Slot();
            slot.entry = upload(make.get(), smooth);
            SLOTS.put(key, slot);
        }
        slot.lastUsed = System.currentTimeMillis();
        return slot.entry;
    }

    private static Entry upload(Raster.Img img, boolean smooth) {
        int w = Math.max(1, img.w()), h = Math.max(1, img.h());
        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int[] px = img.argb();
        for (int y = 0; y < img.h(); y++) {
            for (int x = 0; x < img.w(); x++) {
                int p = px[y * img.w() + x];
                // Straight alpha: the GUI pipeline blends with the source alpha itself.
                ni.setPixel(x, y, p);
            }
        }
        // From 1.21.11 filtering is a sampler the texture holds; smooth pieces get a linear one.
        DynamicTexture tex = new DynamicTexture(() -> "blockyard-ui", ni) {
            {
                if (smooth) sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        Identifier id = Identifier.fromNamespaceAndPath("blockyard-ui", "gen/" + (counter++));
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return new Entry(id, w, h, img.ox(), img.oy());
    }
}
