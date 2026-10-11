package app.blockyard.ui.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.Random;

/** Deep space: twinkling stars, drifting bokeh and swaying light beams, tinted by the accent. */
final class SpaceBackground {
    private float[] sx = new float[0], sy, sr, sp, sv;
    private float[] gx = new float[0], gy, gr, gp, gv;
    private int w = -1, h = -1;
    private long last = System.nanoTime();

    private void seed(int width, int height) {
        w = width;
        h = height;
        Random rnd = new Random(7);
        double px = MinecraftClient.getInstance().getWindow().getScaleFactor();
        int n = (int) Math.max(120, width * height * px * px / 5200);
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
            sv[i] = 0.5f + rnd.nextFloat() * 2f;
        }
        int g = 22;
        gx = new float[g];
        gy = new float[g];
        gr = new float[g];
        gp = new float[g];
        gv = new float[g];
        for (int i = 0; i < g; i++) {
            gx[i] = rnd.nextFloat() * width;
            gy[i] = rnd.nextFloat() * height;
            gr[i] = rnd.nextFloat();
            gp[i] = rnd.nextFloat() * 6.28f;
            gv[i] = 3 + rnd.nextFloat() * 7;
        }
    }

    void render(DrawContext ctx, int width, int height, int accent, float dim) {
        if (width != w || height != h) seed(width, height);
        long now = System.nanoTime();
        float dt = Math.min((now - last) / 1e9f, 0.1f);
        last = now;
        float t = (now / 1_000_000L % 10_000_000L) / 1000f;
        float px = 1f / (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        Identifier glow = Ui.tex("glow"), circle = Ui.tex("circle"), beam = Ui.tex("beam");

        // Base: near-black violet, lit by the accent from the top left.
        ctx.fillGradient(0, 0, width, height, 0xFF0C0A18, 0xFF040308);
        Ui.light(ctx, glow, -width * 0.45f, -height * 0.9f, width * 1.2f, height * 1.6f, Ui.argb(0.30f * dim, accent));
        Ui.light(ctx, glow, width * 0.55f, height * 0.55f, width * 0.8f, height * 0.9f, Ui.argb(0.10f * dim, accent));

        // Two beams hanging from above the top-left corner.
        int warm = Ui.mix(accent, 0xFFB0E0, 0.35f);
        drawBeam(ctx, beam, width * 0.02f, -height * 0.08f, height * 1.45f, height * 0.42f,
                -33 + (float) Math.sin(t * 0.35) * 3.5f, Ui.argb(0.85f * dim, warm));
        drawBeam(ctx, beam, width * 0.10f, -height * 0.12f, height * 1.25f, height * 0.28f,
                -19 + (float) Math.sin(t * 0.27 + 1.7) * 3f, Ui.argb(0.35f * dim, accent));
        // The bright source of the beam.
        Ui.light(ctx, glow, -height * 0.12f, -height * 0.16f, height * 0.36f, height * 0.3f, Ui.argb(0.9f * dim, Ui.mix(warm, 0xFFFFFF, 0.4f)));

        // Stars: tiny, crisp, twinkling, with a slow drift.
        int starColor = Ui.mix(0xFFFFFF, accent, 0.18f);
        for (int i = 0; i < sx.length; i++) {
            sx[i] += (1.5f + sr[i] * 4) * dt * px * 6;
            sy[i] += (0.8f + sr[i] * 2) * dt * px * 6;
            if (sx[i] > width) sx[i] -= width;
            if (sy[i] > height) sy[i] -= height;
            float tw = 0.35f + 0.65f * Math.abs((float) Math.sin(sp[i] + t * sv[i]));
            float size = (1.2f + sr[i] * 2.4f) * px;
            Ui.light(ctx, circle, sx[i] - size / 2, sy[i] - size / 2, size, size, Ui.argb(tw * (0.45f + sr[i] * 0.55f) * dim, starColor));
        }
        // Bokeh: soft accent-colored dots rising slowly.
        for (int i = 0; i < gx.length; i++) {
            gy[i] -= gv[i] * dt * px * 4;
            gx[i] += (float) Math.sin(t * 0.5 + gp[i]) * dt * px * 6;
            if (gy[i] < -10) {
                gy[i] = height + 10;
                gx[i] = (gx[i] * 7.31f + 13) % width;
            }
            float pulse = 0.55f + 0.45f * (float) Math.sin(t * 1.1 + gp[i]);
            float size = (6 + gr[i] * 14) * px;
            Ui.light(ctx, glow, gx[i] - size * 2, gy[i] - size * 2, size * 4, size * 4, Ui.argb(0.35f * pulse * dim, accent));
            Ui.light(ctx, circle, gx[i] - size / 4, gy[i] - size / 4, size / 2, size / 2, Ui.argb(0.75f * pulse * dim, Ui.mix(accent, 0xFFFFFF, 0.25f)));
        }

        // Vignette towards the bottom for readable text.
        ctx.fillGradient(0, height / 2, width, height, 0x00000000, 0x99000000);
    }

    private static void drawBeam(DrawContext ctx, Identifier beam, float x, float y, float length, float spread, float angle, int argb) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
        Ui.light(ctx, beam, -spread / 2, 0, spread, length, argb);
        m.pop();
    }
}
