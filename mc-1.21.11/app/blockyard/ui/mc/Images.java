package app.blockyard.ui.mc;

import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pictures for the views (world icons, favicons, mod icons, screenshots), decoded off the render thread. */
public final class Images {
    static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Blockyard images");
        t.setDaemon(true);
        return t;
    });

    private static final Object NONE = new Object();
    private static final Map<String, Object> LOADED = new HashMap<>();
    private static final Map<String, byte[]> SOURCES = new HashMap<>();

    private Images() {}

    /** A small image file (world icon, mod icon); null until loaded or if there is none. */
    public static BufferedImage file(String key, Path path) {
        Object v = LOADED.get(key);
        if (v != null) return v instanceof BufferedImage b ? b : null;
        LOADED.put(key, NONE);
        if (path == null) return null;
        WORKER.submit(() -> {
            BufferedImage img = null;
            try (InputStream in = Files.newInputStream(path)) {
                img = ImageIO.read(in);
            } catch (Exception ignored) {
                // No picture then.
            }
            BufferedImage done = img;
            Minecraft.getInstance().execute(() -> LOADED.put(key, done == null ? NONE : done));
        });
        return null;
    }

    /** An image from PNG bytes that may change (server favicons). */
    public static BufferedImage bytes(String key, byte[] png) {
        if (png == null) return null;
        if (SOURCES.get(key) != png) {
            SOURCES.put(key, png);
            BufferedImage img = null;
            try {
                img = ImageIO.read(new ByteArrayInputStream(png));
            } catch (Exception ignored) {
                // Broken favicon.
            }
            LOADED.put(key, img == null ? NONE : img);
        }
        Object v = LOADED.get(key);
        return v instanceof BufferedImage b ? b : null;
    }

    /** A big image (screenshot) shrunk to a center-cropped thumbnail in the background. */
    public static BufferedImage thumbnail(String key, Path path, int w, int h) {
        Object v = LOADED.get(key);
        if (v != null) return v instanceof BufferedImage b ? b : null;
        LOADED.put(key, NONE);
        WORKER.submit(() -> {
            BufferedImage thumb = null;
            try (InputStream in = Files.newInputStream(path)) {
                BufferedImage full = ImageIO.read(in);
                if (full != null) {
                    thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = thumb.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    double scale = Math.max(w / (double) full.getWidth(), h / (double) full.getHeight());
                    int dw = (int) Math.round(full.getWidth() * scale), dh = (int) Math.round(full.getHeight() * scale);
                    g.drawImage(full.getScaledInstance(dw, dh, java.awt.Image.SCALE_AREA_AVERAGING), (w - dw) / 2, (h - dh) / 2, null);
                    g.dispose();
                }
            } catch (Exception ignored) {
                // Stays empty.
            }
            BufferedImage done = thumb;
            Minecraft.getInstance().execute(() -> LOADED.put(key, done == null ? NONE : done));
        });
        return null;
    }
}
