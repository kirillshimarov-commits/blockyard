package app.blockyard.ui.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pictures loaded at runtime (world icons, favicons, mod icons, screenshots), smooth-filtered. */
final class Images {
    static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Blockyard images");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Identifier> LOADED = new HashMap<>();
    private static final Map<String, Object> SOURCES = new HashMap<>();
    private static final Map<String, Boolean> PENDING = new HashMap<>();

    private Images() {}

    static Identifier register(String key, NativeImage image) {
        Identifier id = Identifier.of("blockyard-ui", "img/" + Integer.toHexString(key.hashCode()));
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.setFilter(true, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        LOADED.put(key, id);
        return id;
    }

    /** An image from PNG bytes that may change over time (server favicons). */
    static Identifier fromBytes(String key, byte[] png) {
        if (png == null) return null;
        if (SOURCES.get(key) != png) {
            SOURCES.put(key, png);
            try {
                register(key, NativeImage.read(png));
            } catch (Exception e) {
                LOADED.remove(key);
            }
        }
        return LOADED.get(key);
    }

    /** A small image file, read once on the render thread (world icons, mod icons). */
    static Identifier fromFile(String key, Path path) {
        if (LOADED.containsKey(key) || SOURCES.containsKey(key)) return LOADED.get(key);
        SOURCES.put(key, Boolean.TRUE);
        if (path != null && Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                return register(key, NativeImage.read(in));
            } catch (Exception ignored) {
                // No picture then.
            }
        }
        return null;
    }

    /** A big image (screenshot) read and shrunk to a thumbnail in the background. */
    static Identifier thumbnail(String key, Path path, int w, int h) {
        Identifier id = LOADED.get(key);
        if (id != null || PENDING.containsKey(key)) return id;
        PENDING.put(key, Boolean.TRUE);
        WORKER.submit(() -> {
            try (InputStream in = Files.newInputStream(path); NativeImage full = NativeImage.read(in)) {
                NativeImage thumb = new NativeImage(w, h, false);
                // Center crop to the thumbnail's aspect ratio, nearest sampling with 2x2 averaging.
                float scale = Math.min(full.getWidth() / (float) w, full.getHeight() / (float) h);
                float ox = (full.getWidth() - w * scale) / 2, oy = (full.getHeight() - h * scale) / 2;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int sx = Math.min(full.getWidth() - 2, (int) (ox + x * scale));
                        int sy = Math.min(full.getHeight() - 2, (int) (oy + y * scale));
                        thumb.setColor(x, y, average(full.getColor(sx, sy), full.getColor(sx + 1, sy),
                                full.getColor(sx, sy + 1), full.getColor(sx + 1, sy + 1)));
                    }
                }
                MinecraftClient.getInstance().execute(() -> register(key, thumb));
            } catch (Exception ignored) {
                // Leave it pending: a broken file is not retried every frame.
            }
        });
        return null;
    }

    private static int average(int a, int b, int c, int d) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = ((a >>> shift) & 255) + ((b >>> shift) & 255) + ((c >>> shift) & 255) + ((d >>> shift) & 255);
            out |= (sum / 4) << shift;
        }
        return out;
    }
}
