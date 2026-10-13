/** A star of the home screen's space background, as `SpaceScene` draws it. */
export interface HomeStar {
  x: number;
  y: number;
  r: number;
  /** Twinkle phase and speed. */
  p: number;
  s: number;
}

/** Opacity of a home star at `time` seconds: the same formula for the background and the intro. */
export function homeStarAlpha(star: HomeStar, time: number): number {
  const twinkle = 0.45 + 0.55 * Math.abs(Math.sin(star.p + time * star.s));
  return twinkle * (0.35 + star.r * 0.45);
}

/**
 * Something drawn into the space background's canvas, between its stars and glows. While a layer
 * is set, the background leaves its stars to it, so the layer can end on exactly the same picture.
 */
export interface SpaceLayer {
  /**
   * Draws one frame. Returns how visible the background's glowing particles should be (0..1),
   * or `false` when the layer is finished and the background takes over its stars again.
   */
  draw(
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    now: number,
    home: { stars: HomeStar[]; color: string } | null,
  ): number | false;
}

/** Connects the intro to the space background when it is on screen. */
export const spaceBridge: { hosts: number; layer: SpaceLayer | null } = { hosts: 0, layer: null };
