package app.blockyard.ui.preview;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/** Additive blending (what the game does for light), for the preview. */
final class AddComposite implements Composite {
    private final float opacity;

    AddComposite() {
        this(1f);
    }

    AddComposite(float opacity) {
        this.opacity = opacity;
    }

    public CompositeContext createContext(ColorModel src, ColorModel dst, RenderingHints hints) {
        boolean pre = src.isAlphaPremultiplied();
        return new CompositeContext() {
            public void dispose() {}

            public void compose(Raster s, Raster d, WritableRaster out) {
                int w = Math.min(s.getWidth(), d.getWidth()), h = Math.min(s.getHeight(), d.getHeight());
                int[] sp = new int[4], dp = new int[4];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        s.getPixel(x, y, sp);
                        d.getPixel(x, y, dp);
                        float a = (pre ? 1f : sp[3] / 255f) * opacity;
                        for (int c = 0; c < 3; c++) dp[c] = Math.min(255, Math.round(dp[c] + sp[c] * a));
                        out.setPixel(x, y, dp);
                    }
                }
            }
        };
    }
}
