package app.blockyard.ui.core;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The UI fonts (Manrope and Unbounded), laid out with Java2D: anti-aliased, fractional metrics,
 * with a system fallback for characters the fonts lack. The same code renders the game UI and
 * the offline previews, so they look identical.
 */
public final class Fonts {
    static {
        // Only fonts and images are used; never let AWT open a display next to the game window.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    public enum Face {
        BODY("manrope_semibold.ttf"),
        BOLD("manrope_extrabold.ttf"),
        HEAD("unbounded_semibold.ttf");

        final String file;

        Face(String file) {
            this.file = file;
        }
    }

    /** A face at a size in canvas units, with optional letter spacing (em). */
    public record Style(Face face, float size, float tracking) {
        public Style(Face face, float size) {
            this(face, size, 0);
        }
    }

    public static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final Map<Face, Font> BASE = new EnumMap<>(Face.class);
    private static final Font FALLBACK = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
    private static final Map<String, Float> WIDTHS = new HashMap<>();

    private Fonts() {}

    static Font base(Face face) {
        return BASE.computeIfAbsent(face, f -> {
            try (InputStream in = Fonts.class.getResourceAsStream("/assets/blockyard-ui/font/" + f.file)) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                return new Font(Font.SANS_SERIF, f == Face.BODY ? Font.PLAIN : Font.BOLD, 1);
            }
        });
    }

    /** A text layout at a pixel size; characters the font cannot show use the system font. */
    public static TextLayout layout(String text, Style style, float px) {
        if (text.isEmpty()) text = " ";
        Font font = base(style.face()).deriveFont(px);
        AttributedString as = new AttributedString(text);
        as.addAttribute(TextAttribute.FONT, font);
        if (style.tracking() != 0) as.addAttribute(TextAttribute.TRACKING, style.tracking());
        int bad = font.canDisplayUpTo(text);
        if (bad >= 0) {
            Font fb = FALLBACK.deriveFont(px);
            for (int i = 0; i < text.length(); i++) {
                if (!font.canDisplay(text.charAt(i))) as.addAttribute(TextAttribute.FONT, fb, i, i + 1);
            }
        }
        return new TextLayout(as.getIterator(), FRC);
    }

    /** Advance width in canvas units. */
    public static float width(String text, Style style) {
        if (text == null || text.isEmpty()) return 0;
        String key = style.face().ordinal() + "|" + style.size() + "|" + style.tracking() + "|" + text;
        Float w = WIDTHS.get(key);
        if (w == null) {
            // Measured at a large size for precision, scaled back.
            w = layout(text, style, 100f).getAdvance() * style.size() / 100f;
            if (WIDTHS.size() > 20000) WIDTHS.clear();
            WIDTHS.put(key, w);
        }
        return w;
    }

    /** Ascent and descent in canvas units (the line box CSS centers in a box). */
    public static float ascent(Style style) {
        return metrics(style.face())[0] * style.size();
    }

    public static float descent(Style style) {
        return metrics(style.face())[1] * style.size();
    }

    private static final Map<Face, float[]> METRICS = new EnumMap<>(Face.class);

    /** Ascent and descent per unit of size. */
    private static float[] metrics(Face face) {
        return METRICS.computeIfAbsent(face, f -> {
            java.awt.font.LineMetrics lm = base(f).deriveFont(100f).getLineMetrics("Ag", FRC);
            return new float[]{lm.getAscent() / 100f, lm.getDescent() / 100f};
        });
    }

    public static float lineHeight(Style style) {
        return ascent(style) + descent(style);
    }

    public static String fit(String text, Style style, float maxWidth) {
        if (width(text, style) <= maxWidth) return text;
        while (!text.isEmpty() && width(text + "…", style) > maxWidth) text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}
