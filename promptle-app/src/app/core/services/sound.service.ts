import { inject, Injectable, PLATFORM_ID, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/** A single shaped tone played through the shared AudioContext. */
export interface ToneSpec {
  type?: OscillatorType;   // default 'sine'
  startHz: number;
  endHz?: number;          // omit = no pitch glide
  peakGain?: number;       // default 0.08
  durSec?: number;         // default 0.12
  delaySec?: number;       // schedule offset from now, default 0 (for sequences)
}

/**
 * App-wide sound: one global on/off toggle (persisted to localStorage) and the
 * single code path that reaches the speakers.
 *
 * Every sound in the app goes through `tone()` / `sequence()`, and the mute gate
 * lives there — so the corner toggle governs *all* sound by construction, and no
 * caller has to remember to check `muted()`. The AudioContext is private; nothing
 * outside this service synthesizes audio.
 */
@Injectable({ providedIn: 'root' })
export class SoundService {
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  readonly muted = signal<boolean>(this.load());

  private _ctx: AudioContext | null = null;

  /** Flip the global mute — the one switch that silences/enables the whole app. */
  toggle(): void {
    const muted = !this.muted();
    this.muted.set(muted);
    // Unmuting runs inside the toggle's click gesture — the one moment a
    // suspended AudioContext (autoplay policy) is guaranteed resumable.
    if (!muted) this.context();
    this.persist(muted);
  }

  /**
   * Play a single shaped tone. No-op when muted, on SSR, or if audio is
   * unavailable — so this is the only place the mute gate needs to live.
   */
  tone(spec: ToneSpec): void {
    if (this.muted()) return;
    const ctx = this.context();
    if (!ctx) return;
    const {
      type = 'sine', startHz, endHz = startHz,
      peakGain = 0.08, durSec = 0.12, delaySec = 0,
    } = spec;
    try {
      const t0 = ctx.currentTime + delaySec;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(startHz, t0);
      if (endHz !== startHz) {
        osc.frequency.exponentialRampToValueAtTime(endHz, t0 + durSec * 0.4);
      }
      gain.gain.setValueAtTime(peakGain, t0);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + durSec);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + durSec + 0.01);
    } catch { /* audio unavailable (SSR, autoplay policy) */ }
  }

  /** Play a short ordered motif. Each note carries its own `delaySec`. */
  sequence(notes: ToneSpec[]): void {
    for (const note of notes) this.tone(note);
  }

  // --- Named game-event cues -------------------------------------------------
  // Thin wrappers over tone()/sequence() so callers write `sound.roundStart()`
  // and every recipe lives (and is tuned) in one place. All inherit the mute gate.

  /** Round entry (PROMPTING & GUESSING) — bright 3-note up arpeggio. */
  roundStart(): void {
    this.sequence([
      { startHz: 440, durSec: 0.12, peakGain: 0.07 },
      { startHz: 554, durSec: 0.12, peakGain: 0.07, delaySec: 0.10 },
      { startHz: 659, durSec: 0.16, peakGain: 0.07, delaySec: 0.20 },
    ]);
  }

  /** Entering the image-generation wait — soft rising shimmer. */
  phaseGenerating(): void {
    this.tone({ type: 'triangle', startHz: 300, endHz: 500, durSec: 0.45, peakGain: 0.05 });
  }

  /** Results screen reveal — warm major-chord bloom (shell owns this cue). */
  resultsReveal(): void {
    this.sequence([
      { type: 'triangle', startHz: 523, durSec: 0.5, peakGain: 0.06 },
      { type: 'triangle', startHz: 659, durSec: 0.5, peakGain: 0.05, delaySec: 0.06 },
      { type: 'triangle', startHz: 784, durSec: 0.55, peakGain: 0.05, delaySec: 0.12 },
    ]);
  }

  /** Local player's own prompt/guess accepted — short confident rise. */
  submitConfirm(): void {
    this.tone({ startHz: 660, endHz: 880, durSec: 0.1, peakGain: 0.07 });
  }

  /** A round image landed — gentle chime. */
  imageReady(): void {
    this.tone({ type: 'triangle', startHz: 784, endHz: 1047, durSec: 0.18, peakGain: 0.06 });
  }

  /** Another player joined the lobby — warm two-note up. */
  playerJoined(): void {
    this.sequence([
      { startHz: 523, durSec: 0.1, peakGain: 0.06 },
      { startHz: 659, durSec: 0.12, peakGain: 0.06, delaySec: 0.09 },
    ]);
  }

  /** A player left the lobby — gentle two-note down. */
  playerLeft(): void {
    this.sequence([
      { startHz: 440, durSec: 0.1, peakGain: 0.05 },
      { startHz: 330, durSec: 0.14, peakGain: 0.05, delaySec: 0.09 },
    ]);
  }

  /** Game start flourish — celebratory 4-note run. */
  gameStarted(): void {
    this.sequence([
      { startHz: 523, durSec: 0.1, peakGain: 0.07 },
      { startHz: 659, durSec: 0.1, peakGain: 0.07, delaySec: 0.08 },
      { startHz: 784, durSec: 0.1, peakGain: 0.07, delaySec: 0.16 },
      { startHz: 1047, durSec: 0.18, peakGain: 0.07, delaySec: 0.24 },
    ]);
  }

  /** Timer warning tick (10s / 5s) — short, slightly louder square blip. */
  timerWarning(): void {
    this.tone({ type: 'square', startHz: 880, durSec: 0.06, peakGain: 0.05 });
  }

  /** Final countdown (≤3s) — double urgent beep. */
  timerFinal(): void {
    this.sequence([
      { type: 'square', startHz: 988, durSec: 0.07, peakGain: 0.06 },
      { type: 'square', startHz: 988, durSec: 0.07, peakGain: 0.06, delaySec: 0.12 },
    ]);
  }

  /** Showcase moves to the next chain — soft downward whoosh. */
  showcaseAdvance(): void {
    this.tone({ startHz: 600, endHz: 400, durSec: 0.12, peakGain: 0.05 });
  }

  /** Showcase typewriter keystroke — tiny dry click, quiet enough to fire rapidly. */
  typeTick(): void {
    this.tone({ type: 'square', startHz: 1500, durSec: 0.018, peakGain: 0.014 });
  }

  /** Showcase image bubble develops in — short bright blip. */
  imageBlip(): void {
    this.tone({ type: 'triangle', startHz: 660, endHz: 990, durSec: 0.14, peakGain: 0.05 });
  }

  // --- Loading-screen (GENERATING) cues -------------------------------------
  // The interactive pixel-phone toy on the generation-wait screen. Kept quiet
  // and varied so repeated tapping stays pleasant rather than grating.

  /** Screen-cell tap — quick bright plink. */
  cellPop(): void {
    this.tone({ type: 'triangle', startHz: 880, endHz: 1320, durSec: 0.08, peakGain: 0.05 });
  }

  /** Phone icon rings — two-tone trill. */
  phoneRing(): void {
    this.sequence([
      { type: 'square', startHz: 1175, durSec: 0.08, peakGain: 0.045 },
      { type: 'square', startHz: 1480, durSec: 0.10, peakGain: 0.045, delaySec: 0.10 },
    ]);
  }

  /** Btn 0 Static — buzzy sawtooth burst. */
  fxStatic(): void {
    this.tone({ type: 'sawtooth', startHz: 220, endHz: 180, durSec: 0.18, peakGain: 0.04 });
  }

  /** Btn 1 Pixel Rain — descending cascade. */
  fxPixelRain(): void {
    this.sequence([
      { startHz: 1047, durSec: 0.10, peakGain: 0.05 },
      { startHz: 784, durSec: 0.10, peakGain: 0.05, delaySec: 0.08 },
      { startHz: 523, durSec: 0.12, peakGain: 0.05, delaySec: 0.16 },
    ]);
  }

  /** Btn 2 Ripple — radial glide outward. */
  fxRipple(): void {
    this.tone({ startHz: 440, endHz: 880, durSec: 0.30, peakGain: 0.05 });
  }

  /** Btn 4 Blackout — bass drop, then a rising relight. */
  fxBlackout(): void {
    this.sequence([
      { startHz: 300, endHz: 120, durSec: 0.20, peakGain: 0.06 },
      { type: 'triangle', startHz: 330, endHz: 660, durSec: 0.30, peakGain: 0.04, delaySec: 0.30 },
    ]);
  }

  /** Btn 5 Screen toggle — power blip; on = rise, off = fall. */
  fxScreenToggle(on: boolean): void {
    this.tone(on
      ? { type: 'triangle', startHz: 440, endHz: 784, durSec: 0.14, peakGain: 0.05 }
      : { type: 'triangle', startHz: 784, endHz: 220, durSec: 0.18, peakGain: 0.05 });
  }

  /** Btn 6 Heartbeat — two soft low thumps. */
  fxHeartbeat(): void {
    this.sequence([
      { startHz: 160, durSec: 0.14, peakGain: 0.06 },
      { startHz: 160, durSec: 0.16, peakGain: 0.06, delaySec: 0.28 },
    ]);
  }

  /** Btn 7 Disco — bright strobe stabs. */
  fxDisco(): void {
    this.sequence([
      { type: 'square', startHz: 988, durSec: 0.08, peakGain: 0.045 },
      { type: 'square', startHz: 1319, durSec: 0.08, peakGain: 0.045, delaySec: 0.12 },
      { type: 'square', startHz: 1568, durSec: 0.10, peakGain: 0.045, delaySec: 0.24 },
    ]);
  }

  /** Btn 8 Reverse Wave — downward sweep (mirror of Ripple). */
  fxReverseWave(): void {
    this.tone({ startHz: 700, endHz: 350, durSec: 0.30, peakGain: 0.05 });
  }

  /** Lazily-created / resumed shared AudioContext; null on SSR / when unsupported. Private. */
  private context(): AudioContext | null {
    if (!this.isBrowser) return null;
    try {
      this._ctx ??= new AudioContext();
    } catch {
      return null;
    }
    if (this._ctx.state === 'suspended') void this._ctx.resume();
    return this._ctx;
  }

  private load(): boolean {
    if (!this.isBrowser) return false;
    try { return localStorage.getItem('promptle_muted') === '1'; } catch { return false; }
  }

  private persist(muted: boolean): void {
    if (!this.isBrowser) return;
    try { localStorage.setItem('promptle_muted', muted ? '1' : '0'); } catch { /* storage unavailable */ }
  }
}
