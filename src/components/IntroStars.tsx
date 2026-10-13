import { useEffect, useRef } from "react";
import { type HomeStar, homeStarAlpha, type SpaceLayer, spaceBridge } from "./spaceBridge";
import { sounds } from "../sounds";

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
/** Seconds after the burst when the flight has slowed down and the stars start settling. */
const SETTLE_AT = 2.6;
/** Seconds the stars take to settle into the home screen's stars. */
const SETTLE = 1.6;
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
  /** This frame's screen position, radius and opacity; `visible` is false when off the picture. */
  sx: number;
  sy: number;
  sr: number;
  sa: number;
  visible: boolean;
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
      sx: 0,
      sy: 0,
      sr: 0,
      sa: 0,
      visible: false,
    };
  });
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;
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
 * For every home star, the shard that will fly into it: the nearest one still free on screen.
 * -1 where no shard is left; such home stars simply light up in place.
 */
function pairUp(home: HomeStar[], shards: Shard[]): Int32Array {
  const free = shards.map((s, i) => (s.visible ? i : -1)).filter((i) => i >= 0);
  const pairs = new Int32Array(home.length).fill(-1);
  for (let j = 0; j < home.length && free.length > 0; j++) {
    let best = 0;
    let bestDist = Infinity;
    for (let k = 0; k < free.length; k++) {
      const s = shards[free[k]];
      const dist = (s.sx - home[j].x) ** 2 + (s.sy - home[j].y) ** 2;
      if (dist < bestDist) {
        bestDist = dist;
        best = k;
      }
    }
    pairs[j] = free[best];
    free[best] = free[free.length - 1];
    free.pop();
  }
  return pairs;
}

/**
 * The intro after the greeting: a big star rises from the bottom, bursts into a field of small
 * stars, and the camera flies through them. The flight slows down (`onReveal`: the interface
 * fades in) and the stars settle exactly into the home screen's stars (`onEnd`), so the last
 * frame of the intro is the home screen.
 */
function createIntro(onReveal: () => void, onEnd: () => void): SpaceLayer {
  const shards = makeShards();
  const burstAt = RISE + SWELL;
  let start = -1;
  let last = 0;
  let camera = 0;
  let revealed = false;
  let burst = false;
  let pairs: Int32Array | null = null;
  let pairedFor: HomeStar[] | null = null;
  let paired: Uint8Array = new Uint8Array(COUNT);

  return {
    draw(ctx, w, h, now, home) {
      if (start < 0) {
        start = now;
        last = now;
        sounds.rise();
      }
      const t = (now - start) / 1000;
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const cx = w / 2;
      const cy = h / 2;
      const focal = Math.min(w, h) * 0.6;

      if (t < burstAt) {
        // The big star rises, twinkles, then swells.
        const rise = easeOutCubic(clamp01(t / RISE));
        const y = h + 140 - (h + 140 - cy) * rise;
        const swell = clamp01((t - RISE) / SWELL);
        const scale = (0.6 + 0.4 * rise) * (1 + 0.05 * Math.sin(t * 12)) * (1 + 0.8 * swell * swell);
        drawBigStar(ctx, cx, y, scale, 0.75 + 0.25 * swell, t, 1 - rise);
        return 0;
      }

      const tb = t - burstAt;
      if (!burst) {
        burst = true;
        sounds.burst();
      }
      const settle =easeInOut(clamp01((tb - SETTLE_AT) / SETTLE));
      if (tb >= SETTLE_AT + SETTLE) {
        // The stars sit exactly where the home screen draws its own: hand them over.
        onEnd();
        return false;
      }
      if (!revealed && tb >= SETTLE_AT) {
        revealed = true;
        onReveal();
      }

      const spread = easeOutExpo(clamp01(tb / SPREAD));
      // The camera speeds up, cruises, then glides to a stop for the stars to settle.
      const cruise = 1.5 + 14.5 * easeInOut(clamp01(tb / 1.4));
      const brake = 1 - 0.98 * easeInOut(clamp01((tb - 1.5) / 1.4));
      camera += cruise * brake * dt;

      for (const s of shards) {
        if (spread >= 1 && tb < SETTLE_AT && s.z - camera < NEAR) {
          // Flown past: reuse the star far ahead, so the field never runs out.
          s.z += DEPTH;
          s.seen = false;
        }
        const z = STAR_Z + (s.z - STAR_Z) * spread;
        const d = z - camera;
        s.visible = false;
        if (d < NEAR) continue;
        s.sx = cx + ((s.x * spread) / d) * focal;
        s.sy = cy + ((s.y * spread) / d) * focal;
        s.sr = Math.min(9, (s.size * 6) / d) / 2;
        s.sa = clamp01((DEPTH - d) / (DEPTH * 0.6)) * clamp01(tb * 4);
        s.visible = s.sa > 0.01 && s.sx > -40 && s.sx < w + 40 && s.sy > -40 && s.sy < h + 40;
      }

      if (settle <= 0) {
        // Flight: streaks behind the stars that rush past.
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        ctx.lineCap = "round";
        for (const s of shards) {
          if (!s.visible) {
            s.seen = false;
            continue;
          }
          let fromX = s.px;
          let fromY = s.py;
          const dx = s.sx - fromX;
          const dy = s.sy - fromY;
          const len = Math.hypot(dx, dy);
          if (s.seen && len > 180) {
            // Keep streaks of the closest stars from slashing across the whole screen.
            fromX = s.sx - (dx / len) * 180;
            fromY = s.sy - (dy / len) * 180;
          }
          if (s.seen && len > 0.5) {
            ctx.strokeStyle = `rgba(${s.color},${s.sa})`;
            ctx.lineWidth = s.sr * 2;
            ctx.beginPath();
            ctx.moveTo(fromX, fromY);
            ctx.lineTo(s.sx, s.sy);
            ctx.stroke();
          } else {
            ctx.fillStyle = `rgba(${s.color},${s.sa})`;
            ctx.beginPath();
            ctx.arc(s.sx, s.sy, s.sr, 0, Math.PI * 2);
            ctx.fill();
          }
          if (s.sr > 1.5) {
            ctx.fillStyle = `rgba(${s.color},${s.sa * 0.12})`;
            ctx.beginPath();
            ctx.arc(s.sx, s.sy, s.sr * 4.4, 0, Math.PI * 2);
            ctx.fill();
          }
          s.px = s.sx;
          s.py = s.sy;
          s.seen = true;
        }
        ctx.restore();
      } else {
        // Settling: every home star pulls in its nearest shard, which takes on its size and light.
        const stars = home?.stars ?? [];
        if (pairedFor !== stars) {
          pairs = pairUp(stars, shards);
          pairedFor = stars;
          paired = new Uint8Array(COUNT);
          for (const i of pairs) if (i >= 0) paired[i] = 1;
        }
        const time = now / 1000;
        for (let i = 0; i < COUNT; i++) {
          const s = shards[i];
          if (paired[i] || !s.visible) continue;
          ctx.fillStyle = `rgba(${s.color},${s.sa * (1 - settle)})`;
          ctx.beginPath();
          ctx.arc(s.sx, s.sy, s.sr, 0, Math.PI * 2);
          ctx.fill();
        }
        for (let j = 0; j < stars.length; j++) {
          const target = stars[j];
          const targetAlpha = homeStarAlpha(target, time);
          const i = pairs ? pairs[j] : -1;
          if (i < 0) {
            ctx.fillStyle = `rgba(${home!.color}, ${targetAlpha * settle})`;
            ctx.beginPath();
            ctx.arc(target.x, target.y, target.r, 0, Math.PI * 2);
            ctx.fill();
            continue;
          }
          const s = shards[i];
          const x = lerp(s.visible ? s.sx : s.px, target.x, settle);
          const y = lerp(s.visible ? s.sy : s.py, target.y, settle);
          const r = lerp(s.sr, target.r, settle);
          ctx.fillStyle = `rgba(${s.color},${s.sa * (1 - settle)})`;
          ctx.beginPath();
          ctx.arc(x, y, r, 0, Math.PI * 2);
          ctx.fill();
          ctx.fillStyle = `rgba(${home!.color}, ${targetAlpha * settle})`;
          ctx.fill();
        }
      }

      // What is left of the big star fades while the shards leave it.
      drawBigStar(ctx, cx, cy, 1.8 + tb * 3, clamp01(1 - tb / 0.3), t, 0);

      // Shock wave and flash of the burst.
      if (tb < 0.8) {
        const k = tb / 0.8;
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        ctx.strokeStyle = `rgba(190,210,255,${0.55 * (1 - k)})`;
        ctx.lineWidth = 2 + 6 * (1 - k);
        ctx.beginPath();
        ctx.arc(cx, cy, easeOutCubic(k) * Math.hypot(w, h) * 0.55, 0, Math.PI * 2);
        ctx.stroke();
        if (tb < 0.5) {
          const flash = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.hypot(w, h) * 0.6);
          flash.addColorStop(0, `rgba(225,235,255,${0.8 * (1 - tb / 0.5) ** 2})`);
          flash.addColorStop(1, "rgba(225,235,255,0)");
          ctx.fillStyle = flash;
          ctx.fillRect(0, 0, w, h);
        }
        ctx.restore();
      }

      // The background's glowing particles come back while the stars settle.
      return settle;
    },
  };
}

/**
 * Runs the intro. With the space background on screen it plays inside its canvas, so it ends on
 * the background's own stars; otherwise it draws into a canvas of its own and its stars fade out.
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
    const layer = createIntro(
      () => onRevealRef.current(),
      () => onEndRef.current(),
    );

    if (spaceBridge.hosts > 0) {
      spaceBridge.layer = layer;
      return () => {
        if (spaceBridge.layer === layer) spaceBridge.layer = null;
      };
    }

    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) {
      onRevealRef.current();
      onEndRef.current();
      return;
    }
    let w = 0;
    let h = 0;
    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);
    let frame = 0;
    const loop = (now: number) => {
      ctx.clearRect(0, 0, w, h);
      if (layer.draw(ctx, w, h, now, null) !== false) frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", resize);
    };
  }, [running]);

  return <canvas ref={canvasRef} className="splash-canvas" />;
}
