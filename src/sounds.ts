/**
 * Interface sounds, synthesized with Web Audio: no files to ship, and they follow the volume
 * setting. Every sound is short and quiet; nothing plays when sounds are off.
 */

let enabled = true;
let volume = 0.6;
let ctx: AudioContext | null = null;
let noise: AudioBuffer | null = null;

export function configureSounds(on: boolean, level: number) {
  enabled = on;
  volume = Math.min(1, Math.max(0, level));
}

function audio(): AudioContext | null {
  if (!enabled || volume <= 0) return null;
  try {
    ctx ??= new AudioContext();
    if (ctx.state === "suspended") void ctx.resume();
    return ctx;
  } catch {
    return null;
  }
}

/** A single note with a fast attack and an exponential fade, optionally gliding in pitch. */
function tone(
  ac: AudioContext,
  { freq, to, at = 0, dur, gain, type = "sine" }: { freq: number; to?: number; at?: number; dur: number; gain: number; type?: OscillatorType },
) {
  const start = ac.currentTime + at;
  const osc = ac.createOscillator();
  const amp = ac.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, start);
  if (to) osc.frequency.exponentialRampToValueAtTime(to, start + dur);
  amp.gain.setValueAtTime(0.0001, start);
  amp.gain.exponentialRampToValueAtTime(gain * volume, start + Math.min(0.012, dur / 4));
  amp.gain.exponentialRampToValueAtTime(0.0001, start + dur);
  osc.connect(amp).connect(ac.destination);
  osc.start(start);
  osc.stop(start + dur + 0.05);
}

function noiseBuffer(ac: AudioContext): AudioBuffer {
  if (noise && noise.sampleRate === ac.sampleRate) return noise;
  const length = Math.round(ac.sampleRate * 2);
  noise = ac.createBuffer(1, length, ac.sampleRate);
  const data = noise.getChannelData(0);
  for (let i = 0; i < length; i++) data[i] = Math.random() * 2 - 1;
  return noise;
}

export const sounds = {
  /** Moving between tiles and tabs. */
  tick() {
    const ac = audio();
    if (!ac) return;
    tone(ac, { freq: 1900, dur: 0.045, gain: 0.05 });
    tone(ac, { freq: 950, dur: 0.03, gain: 0.025, type: "triangle" });
  },

  /** Pressing "Play": a short rising chime. */
  play() {
    const ac = audio();
    if (!ac) return;
    tone(ac, { freq: 659.25, dur: 0.5, gain: 0.08 });
    tone(ac, { freq: 987.77, at: 0.09, dur: 0.6, gain: 0.07 });
    tone(ac, { freq: 1318.5, at: 0.18, dur: 0.7, gain: 0.04 });
    tone(ac, { freq: 220, to: 330, dur: 0.6, gain: 0.05, type: "triangle" });
  },

  /** The big star of the intro rising from the bottom. */
  rise() {
    const ac = audio();
    if (!ac) return;
    tone(ac, { freq: 196, to: 392, dur: 1.4, gain: 0.05 });
    tone(ac, { freq: 293.66, to: 587.33, at: 0.1, dur: 1.3, gain: 0.03, type: "triangle" });
  },

  /** The big star bursting: a soft boom, a whoosh and a sparkle. */
  burst() {
    const ac = audio();
    if (!ac) return;
    const start = ac.currentTime;
    const src = ac.createBufferSource();
    src.buffer = noiseBuffer(ac);
    const filter = ac.createBiquadFilter();
    filter.type = "lowpass";
    filter.frequency.setValueAtTime(5000, start);
    filter.frequency.exponentialRampToValueAtTime(250, start + 1.4);
    const amp = ac.createGain();
    amp.gain.setValueAtTime(0.0001, start);
    amp.gain.exponentialRampToValueAtTime(0.22 * volume, start + 0.015);
    amp.gain.exponentialRampToValueAtTime(0.0001, start + 1.5);
    src.connect(filter).connect(amp).connect(ac.destination);
    src.start(start);
    src.stop(start + 1.6);

    tone(ac, { freq: 110, to: 38, dur: 0.9, gain: 0.32 });
    for (let i = 0; i < 6; i++) {
      tone(ac, { freq: 2000 + Math.random() * 2400, at: 0.08 + i * 0.07 + Math.random() * 0.05, dur: 0.35, gain: 0.025 });
    }
  },
};
