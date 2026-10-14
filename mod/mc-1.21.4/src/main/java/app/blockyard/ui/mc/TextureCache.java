package app.blockyard.ui.mc;

import app.blockyard.ui.core.Raster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

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
                MinecraftClient.getInstance().getTextureManager().destroyTexture(s.entry.id());
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
                int a = p >>> 24;
                int r = ((p >> 16) & 255) * a / 255, g = ((p >> 8) & 255) * a / 255, b = (p & 255) * a / 255;
                // Colors premultiplied so filtering and blending stay exact.
                ni.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        NativeImageBackedTexture tex = new NativeImageBackedTexture(ni);
        tex.setFilter(smooth, false);
        Identifier id = Identifier.of("blockyard-ui", "gen/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        return new Entry(id, w, h, img.ox(), img.oy());
    }
}
