import { useCallback, useEffect, useState } from "react";
import IntroStars from "./IntroStars";

/**
 * Intro: the logo lights up and the greeting appears, then a big star rises, bursts into
 * a star field the camera flies through, and everything fades into the home screen.
 * A click skips it.
 */
export default function Splash({ name, onDone }: { name: string; onDone: () => void }) {
  const [phase, setPhase] = useState<"greet" | "stars" | "leaving">("greet");
  const finish = useCallback(() => setPhase("leaving"), []);

  useEffect(() => {
    const stars = window.setTimeout(() => setPhase("stars"), 1900);
    return () => window.clearTimeout(stars);
  }, []);

  useEffect(() => {
    if (phase !== "leaving") return;
    const done = window.setTimeout(onDone, 650);
    return () => window.clearTimeout(done);
  }, [phase, onDone]);

  return (
    <div className={`splash ${phase !== "greet" ? "stars" : ""} ${phase === "leaving" ? "leaving" : ""}`} onClick={onDone}>
      <IntroStars running={phase !== "greet"} onEnd={finish} />
      <div className="splash-greet">
        <img className="splash-logo" src="/logo.svg" alt="" />
        <div className="splash-title">
          Добро пожаловать{name ? `, ${name}` : ""}
        </div>
        <div className="splash-sub">Blockyard</div>
      </div>
    </div>
  );
}
