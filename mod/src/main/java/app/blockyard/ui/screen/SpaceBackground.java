package app.blockyard.ui.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

import java.util.Random;

/** Starfield with drifting glows and two swaying light beams, tinted by the accent color. */
final class SpaceBackground {
    private float[] sx = new float[0], sy, sr, sp, sv;
    private float[] gx = new float[0], gy, gp, gv;
    private int w = -1, h = -1;
    private long last = System.nanoTime();

    private void seed(int width, int height) {
        w = width;
        h = height;
        Random rnd = new Random(42);
        int n = Math.max(60, width * height / 900);
        sx = new float[n];
        sy = new float[n];
        sr = new float[n];
        sp = new float[n];
        sv = new float[n];
        for (int i = 0; i < n; i++) {
            sx[i] = rnd.nextFloat() * width;
            sy[i] = rnd.nextFloat() * height;
            sr[i] = rnd.nextFloat();
            sp[i] = rnd.nextFloat() * 6.28f;
            sv[i] = 0.6f + rnd.nextFloat() * 1.8f;
        }
        int g = n / 25 + 6;
        gx = new float[g];
        gy = new float[g];
        gp = new float[g];
        gv = new float[g];
        for (int i = 0; i < g; i++) {
            gx[i] = rnd.nextFloat() * width;
            gy[i] = rnd.nextFloat() * height;
            gp[i] = rnd.nextFloat() * 6.28f;
            gv[i] = 4 + rnd.nextFloat() * 6;
        }
    }

    void render(DrawContext ctx, int width, int height, int accent) {
        if (width != w || height != h) seed(width, height);
        long now = System.nanoTime();
        float dt = Math.min((now - last) / 1e9f, 0.1f);
        last = now;
        float t = (now / 1_000_000L % 1_000_000L) / 1000f;

        // Deep space with the accent glowing from the top left.
        ctx.fillGradient(0, 0, width, height, 0xFF0D0A1C, 0xFF050409);
        int glow = Draw.mix(0x0D0A1C, accent, 0.55f);
        ctx.fillGradient(0, 0, width, height / 2, Draw.argb(110, glow), Draw.argb(0, glow));

        beam(ctx, width, height, accent, -38 + (float) Math.sin(t * 0.45) * 4, width * 0.05f, 0.9f);
        beam(ctx, width, height, accent, -25 + (float) Math.sin(t * 0.33 + 2) * 3, width * 0.16f, 0.45f);

        int star = Draw.mix(0xDCDCE6, accent, 0.15f);
        for (int i = 0; i < sx.length; i++) {
            sx[i] += (2 + sr[i] * 7) * dt * 0.5f;
            sy[i] += (1 + sr[i] * 4) * dt * 0.5f;
            if (sx[i] > width) sx[i] -= width;
            if (sy[i] > height) sy[i] -= height;
            float tw = 0.45f + 0.55f * Math.abs((float) Math.sin(sp[i] + t * sv[i]));
            int a = (int) (255 * tw * (0.35f + sr[i] * 0.5f));
            int x = (int) sx[i], y = (int) sy[i];
            ctx.fill(x, y, x + 1, y + 1, Draw.argb(a, star));
        }
        for (int i = 0; i < gx.length; i++) {
            gy[i] -= gv[i] * dt;
            gx[i] += (float) Math.sin(t * 0.6 + gp[i]) * 3 * dt;
            if (gy[i] < -4) {
                gy[i] = height + 4;
            }
            float pulse = 0.6f + 0.4f * (float) Math.sin(t * 1.3 + gp[i]);
            int x = (int) gx[i], y = (int) gy[i];
            ctx.fill(x - 2, y - 1, x + 3, y + 2, Draw.argb((int) (40 * pulse), accent));
            ctx.fill(x - 1, y - 2, x + 2, y + 3, Draw.argb((int) (40 * pulse), accent));
            ctx.fill(x, y, x + 1, y + 1, Draw.argb((int) (220 * pulse), Draw.mix(accent, 0xFFFFFF, 0.4f)));
        }

        // Vignette.
        ctx.fillGradient(0, height * 2 / 3, width, height, 0x00000000, 0x88000000);
    }

    /** A cone of light hanging from above the top-left corner, built from soft horizontal strips. */
    private static void beam(DrawContext ctx, int width, int height, int accent, float angle, float originX, float strength) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(originX, -height * 0.25f, 0);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
        int len = (int) (height * 1.6f);
        int light = Draw.mix(accent, 0xFFFFFF, 0.45f);
        for (int y = 0; y < len; y += 3) {
            float k = y / (float) len;
            float half = 4 + y * 0.22f;
            float fade = (1 - k) * (1 - k) * strength;
            int color = Draw.mix(light, accent, Math.min(1, k * 2));
            // Three nested layers fake a soft falloff towards the edges.
            ctx.fill((int) -half, y, (int) half, y + 3, Draw.argb((int) (18 * fade), color));
            ctx.fill((int) (-half * 0.66f), y, (int) (half * 0.66f), y + 3, Draw.argb((int) (22 * fade), color));
            ctx.fill((int) (-half * 0.33f), y, (int) (half * 0.33f), y + 3, Draw.argb((int) (26 * fade), color));
        }
        m.pop();
    }
}
