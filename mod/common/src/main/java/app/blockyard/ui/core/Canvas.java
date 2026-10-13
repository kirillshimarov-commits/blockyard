package app.blockyard.ui.core;

import java.awt.image.BufferedImage;

/**
 * What the views draw on, in canvas units (the canvas is 270 units tall, as wide as the window's
 * aspect allows). Implemented by the game (textures) and by the offline preview (Java2D).
 */
public interface Canvas {
    float width();

    default float height() {
        return 270f;
    }

    /** Device pixels per canvas unit. */
    float ppu();

    void fillRound(float x, float y, float w, float h, float r, int top, int bottom, float stroke, int strokeColor);

    default void fillRound(float x, float y, float w, float h, float r, int argb) {
        fillRound(x, y, w, h, r, argb, argb, 0, 0);
    }

    /** Soft drop shadow of a rounded rectangle; blur in canvas units, alpha 0..1. */
    void shadow(float x, float y, float w, float h, float r, float blur, float alpha);

    /** Additive soft light, the shape of a CSS radial gradient. */
    void glow(float cx, float cy, float rx, float ry, int argb);

    void disk(float cx, float cy, float r, int argb);

    /** Text with its top-left at (x, y); the line box is ascent + descent tall. */
    void text(String s, Fonts.Style style, float x, float y, int argb);

    void icon(String name, float x, float y, float size, int argb);

    /** A picture in a rounded square; key identifies the picture (it may change over time). */
    void picture(String key, BufferedImage image, float x, float y, float w, float h, float r, float alpha);

    void space(int accent, float seconds);

    /** Darkens or tints the whole canvas. */
    void fillScreen(int argb);

    void clip(float x, float y, float w, float h);

    void unclip();
}
