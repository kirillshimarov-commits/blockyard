package app.blockyard.ui.core;

import java.util.ArrayList;
import java.util.List;

/** One frame of a view: the canvas, the mouse, the time and the clickable regions drawn so far. */
public final class Frame {
    public final Canvas c;
    public final float vw;
    public final float mx, my;
    public final float t;
    public final int accent;
    /** While a popup is open only popup regions react and nothing else shows hover. */
    public boolean modal;
    public final List<Hit> hits = new ArrayList<>();

    public record Hit(float x, float y, float w, float h, Runnable action, boolean popup, Drag drag) {
        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** A slider being dragged gets the mouse x in canvas units. */
    public interface Drag {
        void to(float mouseX);
    }

    public Frame(Canvas c, float mx, float my, float t, int accent, boolean modal) {
        this.c = c;
        this.vw = c.width();
        this.mx = mx;
        this.my = my;
        this.t = t;
        this.accent = accent;
        this.modal = modal;
    }

    public boolean hover(float x, float y, float w, float h) {
        return !modal && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public boolean hoverPopup(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void hit(float x, float y, float w, float h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action, false, null));
    }

    public void popupHit(float x, float y, float w, float h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action, true, null));
    }

    public void dragHit(float x, float y, float w, float h, Drag drag) {
        hits.add(new Hit(x, y, w, h, () -> drag.to(mx), false, drag));
    }

    /** Runs the topmost region under the point; returns the drag handler if it was a slider. */
    public static Hit find(List<Hit> hits, float px, float py, boolean modal) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (modal && !h.popup()) continue;
            if (h.contains(px, py)) return h;
        }
        return null;
    }
}
