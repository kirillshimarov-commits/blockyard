import { useEffect, useRef } from "react";

/** Number of small stars the big one bursts into. */
const COUNT = 1400;
/** Length of the star field along the flight, in scene units. */
const DEPTH = 46;
/** Depth of the big star: it bursts there, in the middle of the screen. */
const STAR_Z = 4;
/** Seconds: the big star rises from the bottom edge to the middle. */
const RISE = 1.2;
/** Seconds: it swells before bursting. */
const SWELL = 0.3;
/** Seconds: the shards fly apart from the burst point. */
const SPREAD = 1.3;
/** Seconds from the burst to the end of the intro. */
const FLIGHT = 4.2;
/** Seconds after the burst when the sky starts melting into the launcher's background. */
const REVEAL = 2.9;
/** Seconds the sky takes to melt away. */
const SKY_FADE = 0.9;
/** Seconds after the burst when the remaining stars start to fade out. */
const STARS_FADE = 3.3;
/** Stars closer to the camera than this have flown past it. */
const NEAR = 0.15;
const COLORS = ["255,255,255", "214,228,255", "176,200,255", "150,176,255", "222,208,255"];

type Shard = {
  /** Where the shard ends up after the burst. */
  x: number;
  y: number;
  z: number;
  size: number;
  color: string;
  /** Screen position in the previous frame, for the motion streak. */
  px: number;
  py: number;
  seen: boolean;
};

function makeShards(): Shard[] {
  return Array.from({ length: COUNT }, () => {
    const angle = Math.random() * Math.PI * 2;
    // Keep a narrow corridor in the middle free, so the camera flies between the stars.
    const r = 0.25 + Math.sqrt(Math.random()) * 7;
    return {
      x: Math.cos(angle) * r,
      y: Math.sin(angle) * r,
      z: 1.2 + Math.random() * DEPTH,
      size: 0.6 + Math.random() ** 3 * 2.4,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      px: 0,
      py: 0,
      seen: false,
    };
  });
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
const easeOutCubic = (v: number) => 1 - (1 - v) ** 3;
const easeOutExpo = (v: number) => (v >= 1 ? 1 : 1 - 2 ** (-10 * v));
const easeInOut = (v: number) => (v < 0.5 ? 2 * v * v : 1 - (-2 * v + 2) ** 2 / 2);

/** The big star: a glowing core with four light spikes and a tail while it rises. */
function drawBigStar(ctx: CanvasRenderingContext2D, x: number, y: number, scale: number, alpha: number, t: number, tail: number) {
  if (alpha <= 0) return;
  ctx.save();
  ctx.globalCompositeOperation = "lighter";

  if (tail > 0) {
    const len = 260 * tail;
    const g = ctx.createLinearGradient(x, y, x, y + len);
    g.addColorStop(0, `rgba(170,200,255,${0.45 * alpha * tail})`);
    g.addColorStop(1, "rgba(170,200,255,0)");
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(x - 6 * scale, y);
    ctx.lineTo(x + 6 * scale, y);
    ctx.lineTo(x, y + len);
    ctx.closePath();
    ctx.fill();
  }

  const halo = ctx.createRadialGradient(x, y, 0, x, y, 150 * scale);
  halo.addColorStop(0, `rgba(130,170,255,${0.42 * alpha})`);
  halo.addColorStop(1, "rgba(130,170,255,0)");
  ctx.fillStyle = halo;
  ctx.beginPath();
  ctx.arc(x, y, 150 * scale, 0, Math.PI * 2);
  ctx.fill();

  ctx.translate(x, y);
  ctx.rotate(t * 0.35);
  const spikes: [number, number][] = [
    [0, 1],
    [Math.PI / 2, 1],
    [Math.PI / 4, 0.55],
    [(3 * Math.PI) / 4, 0.55],
  ];
  for (const [angle, length] of spikes) {
    const l = 120 * scale * length;
    ctx.save();
    ctx.rotate(angle);
    const g = ctx.createLinearGradient(-l, 0, l, 0);
    g.addColorStop(0, "rgba(200,220,255,0)");
    g.addColorStop(0.5, `rgba(235,242,255,${0.9 * alpha})`);
    g.addColorStop(1, "rgba(200,220,255,0)");
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(0, 0, l, 2.6 * scale, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  const core = ctx.createRadialGradient(0, 0, 0, 0, 0, 26 * scale);
  core.addColorStop(0, `rgba(255,255,255,${alpha})`);
  core.addColorStop(0.35, `rgba(205,222,255,${0.85 * alpha})`);
  core.addColorStop(1, "rgba(150,180,255,0)");
  ctx.fillStyle = core;
  ctx.beginPath();
  ctx.arc(0, 0, 26 * scale, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

/**
 * The intro after the greeting: on a dark blue sky a big star rises from the bottom, bursts into
 * a field of small stars, and the camera flies through them. The flight then slows down, the sky
 * melts away (`onReveal`: the launcher fades in underneath) and the last stars fade (`onEnd`).
 */
export default function IntroStars({
  running,
  onReveal,
  onEnd,
}: {
  running: boolean;
  onReveal: () => void;
  onEnd: () => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const onRevealRef = useRef(onReveal);
  onRevealRef.current = onReveal;
  const onEndRef = useRef(onEnd);
  onEndRef.current = onEnd;

  useEffect(() => {
    if (!running) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) {
      onRevealRef.current();
      onEndRef.current();
      return;
    }

    let w = 0;
    let h = 0;
    let sky: CanvasGradient | null = null;
    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      sky = ctx.createRadialGradient(w / 2, h * 0.55, 0, w / 2, h * 0.55, Math.hypot(w, h) * 0.6);
      sky.addColorStop(0, "#0d1d52");
      sky.addColorStop(0.55, "#071233");
      sky.addColorStop(1, "#02060f");
    };
    resize();
    window.addEventListener("resize", resize);

    const shards = makeShards();
    const burstAt = RISE + SWELL;
    let camera = 0;
    let revealed = false;
    let ended = false;
    let frame = 0;
    const start = performance.now();
    let last = start;

    const draw = (now: number) => {
      const t = (now - start) / 1000;
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const cx = w / 2;
      const cy = h / 2;
      const focal = Math.min(w, h) * 0.6;

      // The sky melts away at the end, uncovering the launcher's own space background.
      const skyAlpha = 1 - clamp01((t - RISE - SWELL - REVEAL) / SKY_FADE);
      ctx.globalCompositeOperation = "source-over";
      ctx.clearRect(0, 0, w, h);
      ctx.globalAlpha = skyAlpha;
      ctx.fillStyle = sky ?? "#071233";
      ctx.fillRect(0, 0, w, h);
      ctx.globalAlpha = 1;

      if (t < burstAt) {
        // The big star rises, twinkles, then swells.
        const rise = easeOutCubic(clamp01(t / RISE));
        const y = h + 140 - (h + 140 - cy) * rise;
        const swell = clamp01((t - RISE) / SWELL);
        const scale = (0.6 + 0.4 * rise) * (1 + 0.05 * Math.sin(t * 12)) * (1 + 0.8 * swell * swell);
        drawBigStar(ctx, cx, y, scale, 0.75 + 0.25 * swell, t, 1 - rise);
      } else {
        const tb = t - burstAt;
        const spread = easeOutExpo(clamp01(tb / SPREAD));

        // The camera speeds up, cruises, then glides to an almost still drift, like the home screen's sky.
        const cruise = 1.5 + 14.5 * easeInOut(clamp01(tb / 1.6));
        const brake = 1 - 0.97 * easeInOut(clamp01((tb - 2.2) / 1.5));
        camera += cruise * brake * dt;
        const starsAlpha = 1 - clamp01((tb - STARS_FADE) / (FLIGHT - STARS_FADE));

        ctx.globalCompositeOperation = "lighter";
        ctx.lineCap = "round";
        for (const s of shards) {
          if (spread >= 1 && s.z - camera < NEAR) {
            // Flown past: reuse the star far ahead, so the field never runs out.
            s.z += DEPTH;
            s.seen = false;
          }
          const x = s.x * spread;
          const y = s.y * spread;
          const z = STAR_Z + (s.z - STAR_Z) * spread;
          const d = z - camera;
          if (d < NEAR) {
            s.seen = false;
            continue;
          }
          const sx = cx + (x / d) * focal;
          const sy = cy + (y / d) * focal;
          const size = Math.min(9, (s.size * 6) / d);
          const alpha = clamp01((DEPTH - d) / (DEPTH * 0.6)) * clamp01(tb * 4) * starsAlpha;
          if (alpha <= 0.01) {
            s.seen = false;
            continue;
          }

          let fromX = s.px;
          let fromY = s.py;
          const dx = sx - fromX;
          const dy = sy - fromY;
          const len = Math.hypot(dx, dy);
          if (s.seen && len > 180) {
            // Keep streaks of the closest stars from slashing across the whole screen.
            fromX = sx - (dx / len) * 180;
            fromY = sy - (dy / len) * 180;
          }
          if (s.seen && len > 0.5) {
            ctx.strokeStyle = `rgba(${s.color},${alpha})`;
            ctx.lineWidth = size;
            ctx.beginPath();
            ctx.moveTo(fromX, fromY);
            ctx.lineTo(sx, sy);
            ctx.stroke();
          } else {
            ctx.fillStyle = `rgba(${s.color},${alpha})`;
            ctx.beginPath();
            ctx.arc(sx, sy, size / 2, 0, Math.PI * 2);
            ctx.fill();
          }
          if (size > 3) {
            ctx.fillStyle = `rgba(${s.color},${alpha * 0.12})`;
            ctx.beginPath();
            ctx.arc(sx, sy, size * 2.2, 0, Math.PI * 2);
            ctx.fill();
          }
          s.px = sx;
          s.py = sy;
          s.seen = true;
        }

        // What is left of the big star fades while the shards leave it.
        const fade = clamp01(1 - tb / 0.3);
        drawBigStar(ctx, cx, cy, 1.8 + tb * 3, fade, t, 0);

        // Shock wave and flash of the burst.
        if (tb < 0.8) {
          const k = tb / 0.8;
          ctx.strokeStyle = `rgba(190,210,255,${0.55 * (1 - k)})`;
          ctx.lineWidth = 2 + 6 * (1 - k);
          ctx.beginPath();
          ctx.arc(cx, cy, easeOutCubic(k) * Math.hypot(w, h) * 0.55, 0, Math.PI * 2);
          ctx.stroke();
        }
        if (tb < 0.5) {
          const flash = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.hypot(w, h) * 0.6);
          flash.addColorStop(0, `rgba(225,235,255,${0.8 * (1 - tb / 0.5) ** 2})`);
          flash.addColorStop(1, "rgba(225,235,255,0)");
          ctx.fillStyle = flash;
          ctx.fillRect(0, 0, w, h);
        }

        if (!revealed && tb >= REVEAL) {
          revealed = true;
          onRevealRef.current();
        }
        if (!ended && tb >= FLIGHT) {
          ended = true;
          onEndRef.current();
          return;
        }
      }
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", resize);
    };
  }, [running]);

  return <canvas ref={canvasRef} className="splash-canvas" />;
}
