import { useEffect, useRef } from "react";

interface Star {
  x: number;
  y: number;
  r: number;
  /** Twinkle phase and speed. */
  p: number;
  s: number;
  /** Drift per second, in CSS pixels. */
  vx: number;
  vy: number;
}

interface Glow {
  x: number;
  y: number;
  r: number;
  a: number;
  vx: number;
  vy: number;
  p: number;
}

function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace("#", "").padEnd(6, "0").slice(0, 6), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Starfield with drifting glowing particles and light beams, tinted by the accent color. */
export default function SpaceScene({ accent, animate }: { accent: string; animate: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    const ctx = el.getContext("2d");
    if (!ctx) return;
    const [ar, ag, ab] = hexToRgb(accent);
    // Stars are mostly white with a hint of the accent; glows take the accent fully.
    const starColor = `${Math.round(215 + ar * 0.16)}, ${Math.round(215 + ag * 0.16)}, ${Math.round(225 + ab * 0.12)}`;

    let w = 0;
    let h = 0;
    let stars: Star[] = [];
    let glows: Glow[] = [];

    function seed() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = el!.clientWidth;
      h = el!.clientHeight;
      el!.width = Math.round(w * dpr);
      el!.height = Math.round(h * dpr);
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
      const count = Math.round((w * h) / 2600);
      stars = Array.from({ length: count }, () => {
        const depth = Math.random();
        return {
          x: Math.random() * w,
          y: Math.random() * h,
          r: 0.3 + depth * 1.1,
          p: Math.random() * Math.PI * 2,
          s: 0.6 + Math.random() * 1.8,
          // Nearer stars drift faster: a slow parallax towards the bottom right.
          vx: 2 + depth * 7,
          vy: 1 + depth * 4,
        };
      });
      glows = Array.from({ length: Math.round(count / 28) + 6 }, () => ({
        x: Math.random() * w,
        y: Math.random() * h,
        r: 1.5 + Math.random() * 3.5,
        a: 0.35 + Math.random() * 0.5,
        vx: -6 + Math.random() * 12,
        vy: -10 - Math.random() * 10,
        p: Math.random() * Math.PI * 2,
      }));
    }

    function draw(t: number, dt: number) {
      ctx!.clearRect(0, 0, w, h);
      for (const s of stars) {
        s.x += s.vx * dt;
        s.y += s.vy * dt;
        if (s.x > w + 2) s.x -= w + 4;
        if (s.y > h + 2) s.y -= h + 4;
        const tw = 0.45 + 0.55 * Math.abs(Math.sin(s.p + t * s.s));
        ctx!.fillStyle = `rgba(${starColor}, ${tw * (0.35 + s.r * 0.45)})`;
        ctx!.beginPath();
        ctx!.arc(s.x, s.y, s.r, 0, Math.PI * 2);
        ctx!.fill();
      }
      for (const g of glows) {
        g.x += (g.vx + Math.sin(t * 0.6 + g.p) * 6) * dt;
        g.y += g.vy * dt;
        if (g.y < -20) {
          g.y = h + 20;
          g.x = Math.random() * w;
        }
        if (g.x < -20) g.x = w + 20;
        if (g.x > w + 20) g.x = -20;
        const pulse = 0.6 + 0.4 * Math.sin(t * 1.3 + g.p);
        const grad = ctx!.createRadialGradient(g.x, g.y, 0, g.x, g.y, g.r * 4);
        grad.addColorStop(0, `rgba(${ar}, ${ag}, ${ab}, ${g.a * pulse})`);
        grad.addColorStop(0.35, `rgba(${ar}, ${ag}, ${ab}, ${g.a * pulse * 0.35})`);
        grad.addColorStop(1, `rgba(${ar}, ${ag}, ${ab}, 0)`);
        ctx!.fillStyle = grad;
        ctx!.beginPath();
        ctx!.arc(g.x, g.y, g.r * 4, 0, Math.PI * 2);
        ctx!.fill();
      }
    }

    seed();
    let frame = 0;
    let last = performance.now();
    const loop = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.1);
      last = now;
      draw(now / 1000, dt);
      frame = requestAnimationFrame(loop);
    };
    if (animate) frame = requestAnimationFrame(loop);
    else draw(0, 0);

    const onResize = () => {
      seed();
      if (!animate) draw(0, 0);
    };
    // Stop drawing while the window is hidden (for example, while the game is running).
    const onVisibility = () => {
      if (!animate) return;
      cancelAnimationFrame(frame);
      if (!document.hidden) {
        last = performance.now();
        frame = requestAnimationFrame(loop);
      }
    };
    window.addEventListener("resize", onResize);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", onResize);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [accent, animate]);

  return (
    <div className="space-scene" aria-hidden>
      <div className="space-beam beam-a" />
      <div className="space-beam beam-b" />
      <canvas ref={canvas} />
      <div className="space-vignette" />
    </div>
  );
}
