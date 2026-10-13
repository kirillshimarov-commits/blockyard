import { useCallback, useEffect, useState } from "react";
import IntroStars from "./IntroStars";
import { t } from "../i18n";

/**
 * Intro: the logo lights up and the greeting appears, then a big star rises, bursts into
 * a star field the camera flies through, and the flight slows down into the home screen's sky
 * while the interface fades in. A click skips it.
 */
export default function Splash({ name, onReveal, onDone }: { name: string; onReveal: () => void; onDone: () => void }) {
  const [phase, setPhase] = useState<"greet" | "stars" | "reveal">("greet");

  useEffect(() => {
    const stars = window.setTimeout(() => setPhase("stars"), 1900);
    return () => window.clearTimeout(stars);
  }, []);

  const handOver = useCallback(() => {
    setPhase("reveal");
    onReveal();
  }, [onReveal]);

  return (
    <div className={`splash ${phase}`} onClick={onDone}>
      <IntroStars running={phase !== "greet"} onReveal={handOver} onEnd={onDone} />
      <div className="splash-greet">
        <img className="splash-logo" src="/logo.svg" alt="" />
        <div className="splash-title">{name ? t("Добро пожаловать, {name}", { name }) : t("Добро пожаловать")}</div>
        <div className="splash-sub">Blockyard</div>
      </div>
    </div>
  );
}
