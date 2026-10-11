import { useEffect, useState } from "react";

/** Short intro: the logo lights up, the greeting appears, then everything fades into the home screen. */
export default function Splash({ name, onDone }: { name: string; onDone: () => void }) {
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    const leave = window.setTimeout(() => setLeaving(true), 1900);
    const done = window.setTimeout(onDone, 2500);
    return () => {
      window.clearTimeout(leave);
      window.clearTimeout(done);
    };
  }, [onDone]);

  return (
    <div className={`splash ${leaving ? "leaving" : ""}`} onClick={onDone}>
      <img className="splash-logo" src="/logo.svg" alt="" />
      <div className="splash-title">
        Добро пожаловать{name ? `, ${name}` : ""}
      </div>
      <div className="splash-sub">Blockyard</div>
    </div>
  );
}
