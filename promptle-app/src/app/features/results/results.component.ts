import { Component, HostListener, OnDestroy, OnInit, signal, computed, inject, effect, ElementRef, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';

import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { RoomApiService } from '../../core/services/room-api.service';
import { SoundService } from '../../core/services/sound.service';
import { ChainDto, RoomEvent, ShowcaseAdvancedEvent } from '../../core/models/events.model';
import { PlayerDto } from '../../core/models/player.model';
import { PLAYER_ICONS } from '../../core/models/player-icons';
import { environment } from '../../../environments/environment';
import { StompSubscription } from '@stomp/stompjs';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [],
  styleUrl: './results.component.scss',
  templateUrl: './results.component.html',
})
export class ResultsComponent implements OnInit, OnDestroy {
  @ViewChild('entriesFeed') private entriesFeed?: ElementRef<HTMLElement>;

  private readonly http = inject(HttpClient);
  private readonly webSocketService = inject(WebSocketService);
  private readonly playerService = inject(PlayerService);
  private readonly roomApiService = inject(RoomApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly sound = inject(SoundService);

  /** Showcase audio on/off — backed by the app-wide SoundService. */
  readonly muted = this.sound.muted;

  chains = signal<ChainDto[]>([]);
  players = signal<PlayerDto[]>([]);
  currentChainIndex = signal<number>(0);
  // The showcase reveals one MESSAGE at a time (a text bubble or an image bubble),
  // never both halves of an entry on the same tick. Each entry contributes one
  // step for its text plus one for its image when present.
  revealedStepCount = signal<number>(0);
  isHost = signal<boolean>(false);
  canProceed = signal<boolean>(false);
  exporting = signal<boolean>(false);

  private readonly _allChainsCompleted = signal<boolean>(false);
  readonly allChainsCompleted = this._allChainsCompleted.asReadonly();

  readonly revealIntervalMs: number = environment.showcaseRevealIntervalMs;
  // Per-message base cadence: half the per-entry interval, so a text+image entry
  // paces at roughly one full reveal interval end to end.
  readonly stepIntervalMs: number = Math.max(400, Math.floor(this.revealIntervalMs / 2));
  // Content-aware pacing: dots "think" time before each bubble, then text types
  // out per character (longer guesses type longer, capped), then a short settle.
  readonly textThinkMs: number = Math.round(this.stepIntervalMs * 0.6);
  readonly typeMsPerChar: number = 45;
  readonly maxTypingMs: number = 3000;
  // Post-message "green dwell": the just-finished avatar holds green with the next
  // typing indicator suppressed, so each message visibly lands before the next begins.
  readonly textSettleMs: number = 1000;
  readonly imageThinkMs: number = this.stepIntervalMs;
  readonly imageSettleMs: number = 1000;

  // The text bubble currently being typewritten (null when idle).
  typing = signal<{ entryIndex: number; chars: number } | null>(null);

  // True during the green-dwell window between a finished message and the next
  // typing indicator. Suppresses the pending-dots bubble so the green lands first.
  dwelling = signal<boolean>(false);

  // Avatars whose message has finished (text typewriter complete / image revealed).
  // A rendered avatar reads crimson while its message is "in progress" and flips to
  // green once its key lands here. Keys: `t<i>` for player text, `i<i>` for images.
  settled = signal<Set<string>>(new Set());

  private _stepTimer: ReturnType<typeof setTimeout> | null = null;
  private _typeTimer: ReturnType<typeof setInterval> | null = null;
  private _proceedTimer: ReturnType<typeof setTimeout> | null = null;
  private _roomCode = '';
  private _subs: StompSubscription[] = [];
  // True once we're leaving the showcase for the lobby. The live WebSocket must be
  // kept alive across this navigation so the lobby can reuse it — tearing it down
  // would briefly mark the player disconnected, which corrupts the "waiting for
  // players" reset bookkeeping (ghost-player deletion / missed GAME_RESET).
  private navigatingToLobby = false;

  readonly allRevealed = computed(() => {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    // The last bubble may still be typewriting after its step is revealed.
    return this.revealedStepCount() >= this.stepsIn(chain) && this.typing() === null;
  });

  /** Typing dots are hidden while a bubble typewrites and during the green dwell. */
  readonly visiblePendingStep = computed(() => (this.typing() || this.dwelling() ? null : this.pendingStep()));

  /** The message currently "being typed" — drives the typing-dots indicator. */
  readonly pendingStep = computed<{ kind: 'text' | 'image'; entryIndex: number } | null>(() => {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return null;
    const step = this.revealedStepCount();
    let acc = 0;
    for (let i = 0; i < chain.entries.length; i++) {
      if (step === acc) return { kind: 'text', entryIndex: i };
      acc++;
      if (chain.entries[i].imageUrl) {
        if (step === acc) return { kind: 'image', entryIndex: i };
        acc++;
      }
    }
    return null;
  });

  constructor() {
    effect(() => {
      if (this.allRevealed()) {
        this._proceedTimer = setTimeout(() => this.canProceed.set(true), 550);
        if (this.chains().length > 0 && this.currentChainIndex() >= this.chains().length - 1) {
          this._allChainsCompleted.set(true);
        }
      } else {
        if (this._proceedTimer) { clearTimeout(this._proceedTimer); this._proceedTimer = null; }
        this.canProceed.set(false);
      }
    });

    // Auto-scroll the feed to keep the latest item in view, in two beats:
    //  1. the "typing/drawing" indicator appears (visiblePendingStep) — scroll so
    //     there's room for the bubble that's about to be typed,
    //  2. the message/image lands (revealedStepCount) or finishes (settled) —
    //     scroll so the now-complete bubble fits.
    // Each read below makes the effect re-run on that transition. We deliberately
    // do NOT track typing() per character, so a long guess doesn't scroll on every
    // keystroke — only at the two beats above.
    effect(() => {
      this.revealedStepCount();
      this.visiblePendingStep();
      this.settled();
      setTimeout(() => this.scrollToLatest(), 80);
    });
  }

  /**
   * Scroll the feed down to the last item (the freshly-revealed bubble or the
   * "being typed" indicator — both carry .entry-row) only when it doesn't fully
   * fit at the bottom of the visible area. When the item already fits, the feed
   * stays put, so short chains on a tall screen never jump.
   */
  private scrollToLatest(): void {
    const feed = this.entriesFeed?.nativeElement;
    if (!feed) return;
    const entries = feed.querySelectorAll('.entry-row');
    const last = entries[entries.length - 1] as HTMLElement | undefined;
    if (!last) return;
    const overflowBottom = last.getBoundingClientRect().bottom - feed.getBoundingClientRect().bottom;
    if (overflowBottom > 0) {
      // +12px breathing room below the bubble; the feed's CSS scroll-behavior is
      // smooth, and we pass it explicitly for clarity.
      feed.scrollTo({ top: feed.scrollTop + overflowBottom + 12, behavior: 'smooth' });
    }
  }

  /**
   * A revealed image just finished loading. On the deployed build, generated
   * images load over the network with real latency, so the image bubble still
   * has ~0 height when the reveal effect measured it at +80ms — leaving the feed
   * un-scrolled. Re-running the scroll on the image's own `load` event makes the
   * feed follow the image regardless of how long it took to arrive. (On localhost
   * the image is already loaded by then, so this is a harmless no-op.)
   */
  onImageLoaded(): void {
    this.scrollToLatest();
  }

  ngOnInit(): void {
    this._roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';

    const navState = window.history.state as { chains?: ChainDto[]; hostId?: string; players?: PlayerDto[] };
    if (navState?.chains?.length) {
      this.chains.set(navState.chains);
    }
    if (navState?.players?.length) {
      this.players.set(navState.players);
    }

    const stateHostId = navState?.hostId;
    const stored = this.playerService.loadFromLocalStorage(this._roomCode);
    const token = stored?.playerToken ?? '';
    if (stateHostId && stored?.playerId && stateHostId === stored.playerId) {
      this.isHost.set(true);
    }

    const subscribeToTopics = () => {
      this._subs.push(this.webSocketService.subscribe(`/topic/game/${this._roomCode}`, (msg: unknown) => {
        const event = msg as ShowcaseAdvancedEvent;
        if (event && typeof event.chainIndex === 'number') {
          this.sound.showcaseAdvance();
          this.canProceed.set(false);
          this.currentChainIndex.set(event.chainIndex);
          this.revealedStepCount.set(0);
          this.startRevealInterval();
        }
      }));

      this._subs.push(this.webSocketService.subscribe(`/topic/room/${this._roomCode}`, (msg: unknown) => {
        const event = msg as RoomEvent;
        if (event?.type === 'GAME_RESET') {
          this.navigatingToLobby = true;
          this.router.navigate(['/lobby', this._roomCode]);
        } else if (event?.type === 'HOST_CHANGED' || event?.type === 'PLAYER_LEFT' || event?.type === 'PLAYER_JOINED') {
          // Keep showcase host control in sync if the host changes (so the "Next" control
          // never ends up owned by nobody). Server validates against the live hostId anyway.
          const myId = this.playerService.loadFromLocalStorage(this._roomCode)?.playerId;
          this.isHost.set(!!event.hostId && !!myId && event.hostId === myId);
        }
      }));
    };

    // Reuse the live connection kept by GameComponent; only reconnect on direct navigation / refresh
    if (this.webSocketService.isConnected()) {
      subscribeToTopics();
    } else {
      this.webSocketService.connect(token, this._roomCode, subscribeToTopics);
    }

    this.startRevealInterval();
  }

  getPlayerName(playerId: string | null): string {
    if (!playerId) return '';
    return this.players().find(p => p.id === playerId)?.alias ?? '';
  }

  getAvatarPath(avatarId: string): string {
    return PLAYER_ICONS.find(i => i.id === avatarId)?.path ?? '';
  }

  /** (Re)starts the self-scheduling reveal: think dots → bubble → typewriter → next. */
  startRevealInterval(): void {
    this._clearTimers();
    this.typing.set(null);
    this.dwelling.set(false);
    this.settled.set(new Set());
    this._scheduleNextStep();
  }

  private _scheduleNextStep(): void {
    const pending = this.pendingStep();
    if (!pending) return;
    const thinkMs = pending.kind === 'text' ? this.textThinkMs : this.imageThinkMs;
    this._stepTimer = setTimeout(() => this._revealPending(), thinkMs);
  }

  private _revealPending(): void {
    const pending = this.pendingStep();
    if (!pending) return;
    const chain = this.chains()[this.currentChainIndex()];
    this.revealedStepCount.update(v => v + 1);
    this._messageFeedback(pending.kind);
    if (pending.kind === 'text') {
      this._startTypewriter(pending.entryIndex, chain?.entries[pending.entryIndex]?.text ?? '');
    } else {
      // The "drawing" dots were the in-progress (crimson) phase; once the image
      // lands its Promptle avatar flips green and holds through the dwell.
      this._beginDwell('i' + pending.entryIndex, this.imageSettleMs);
    }
  }

  /** Per-character delay — long texts type faster so the total stays capped. */
  charIntervalFor(length: number): number {
    return Math.max(16, Math.min(this.typeMsPerChar, Math.floor(this.maxTypingMs / Math.max(1, length))));
  }

  private _startTypewriter(entryIndex: number, text: string): void {
    const len = text.length;
    if (len === 0) {
      this._beginDwell('t' + entryIndex, this.textSettleMs);
      return;
    }
    const charMs = this.charIntervalFor(len);
    // Cap keystroke ticks at ~one per 45ms so fast-typing long guesses don't
    // machine-gun the speakers (charMs floors at 16ms).
    const tickEvery = Math.max(1, Math.round(45 / charMs));
    this.typing.set({ entryIndex, chars: 0 });
    this._typeTimer = setInterval(() => {
      const t = this.typing();
      if (!t) return;
      if (t.chars + 1 >= len) {
        this._clearTypeTimer();
        this.typing.set(null);
        this._beginDwell('t' + t.entryIndex, this.textSettleMs);
      } else {
        const chars = t.chars + 1;
        if (!this.muted() && chars % tickEvery === 0) this.sound.typeTick();
        this.typing.set({ entryIndex: t.entryIndex, chars });
      }
    }, charMs);
  }

  /**
   * Flip the just-finished avatar to green, hold it (dots suppressed) for the dwell
   * window, then resume revealing — so each message lands before the next one types.
   */
  private _beginDwell(settledKey: string, ms: number): void {
    this.settled.update(s => new Set(s).add(settledKey));
    this.dwelling.set(true);
    this._stepTimer = setTimeout(() => {
      this.dwelling.set(false);
      this._scheduleNextStep();
    }, ms);
  }

  /** A revealed player-text avatar reads green once its typewriter has finished. */
  isTextSettled(entryIndex: number): boolean {
    return this.settled().has('t' + entryIndex);
  }

  /** A revealed image's Promptle avatar reads green once the image has landed. */
  isImageSettled(entryIndex: number): boolean {
    return this.settled().has('i' + entryIndex);
  }

  /** Text shown for an entry — partial while its bubble is typewriting. */
  displayedText(entryIndex: number, text: string): string {
    const t = this.typing();
    return t && t.entryIndex === entryIndex ? text.slice(0, t.chars) : text;
  }

  isTyping(entryIndex: number): boolean {
    return this.typing()?.entryIndex === entryIndex;
  }

  navigateToChain(index: number): void {
    if (!this.allChainsCompleted()) return;
    this._clearTimers();
    this.typing.set(null);
    this.dwelling.set(false);
    this.currentChainIndex.set(index);
    const chain = this.chains()[index];
    this.revealedStepCount.set(this.stepsIn(chain));
    // Jumping straight to a finished chain: every avatar is already green.
    const all = new Set<string>();
    chain?.entries.forEach((e, i) => {
      all.add('t' + i);
      if (e.imageUrl) all.add('i' + i);
    });
    this.settled.set(all);
    this.canProceed.set(true);
  }

  /** Soft pop + haptic tick per revealed message; both silenced by the mute toggle. */
  private _messageFeedback(kind: 'text' | 'image'): void {
    if (this.muted()) return; // gate the haptic; SoundService cues gate the audio themselves
    try { navigator.vibrate?.(kind === 'image' ? 15 : 10); } catch { /* not supported */ }
    if (kind === 'image') {
      this.sound.imageBlip(); // image bubble "develops" in with a bright blip
    } else {
      // Text bubble pop — the keystroke ticks follow as it typewrites.
      this.sound.tone({ startHz: 520, endHz: 832, peakGain: 0.045, durSec: 0.12 });
    }
  }

  /** Total reveal steps in a chain: one per text bubble plus one per image. */
  stepsIn(chain: ChainDto | undefined): number {
    if (!chain) return 0;
    return chain.entries.reduce((n, e) => n + 1 + (e.imageUrl ? 1 : 0), 0);
  }

  private stepsBefore(chain: ChainDto, entryIndex: number): number {
    let acc = 0;
    for (let i = 0; i < entryIndex && i < chain.entries.length; i++) {
      acc += 1 + (chain.entries[i].imageUrl ? 1 : 0);
    }
    return acc;
  }

  isTextRevealed(entryIndex: number): boolean {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    return this.stepsBefore(chain, entryIndex) < this.revealedStepCount();
  }

  isImageRevealed(entryIndex: number): boolean {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    return this.stepsBefore(chain, entryIndex) + 1 < this.revealedStepCount();
  }

  onNextChain(): void {
    this.webSocketService.send(`/app/room/${this._roomCode}/next-chain`, {});
  }

  /** Enter shortcut for the LOBBY (ready) button once the showcase is over. */
  @HostListener('window:keydown.enter')
  onEnterKey(): void {
    if (!this.allChainsCompleted() || this.exporting()) return;
    this.onBackToLobby();
  }

  onBackToLobby(): void {
    if (this.navigatingToLobby) return; // re-entry guard (held Enter key repeat)
    // Per-player return: tell the server this player has left the showcase, then
    // navigate locally. The room itself only resets once everyone has returned.
    const token = this.playerService.loadFromLocalStorage(this._roomCode)?.playerToken ?? '';
    this.roomApiService.leaveResults(this._roomCode, token).subscribe({ error: () => {} });
    this.navigatingToLobby = true;
    this.router.navigate(['/lobby', this._roomCode]);
  }

  onExportThread(): void {
    if (this.exporting()) return;
    this.exporting.set(true);

    const token = this.playerService.loadFromLocalStorage(this._roomCode)?.playerToken ?? '';
    const chain = this.chains()[this.currentChainIndex()];

    this.http.post(
      `/api/export/${this._roomCode}`,
      { chain, players: this.players() },
      {
        headers: { 'X-Player-Token': token },
        responseType: 'blob'
      }
    ).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `promptle-chain-${this.currentChainIndex() + 1}.gif`;
        a.click();
        URL.revokeObjectURL(url);
        this.exporting.set(false);
      },
      error: () => {
        this.exporting.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    this._clearTimers();
    if (this._proceedTimer) clearTimeout(this._proceedTimer);
    // Drop our own topic subscriptions so their callbacks don't fire on the dead
    // component, but keep the socket open when handing off to the lobby.
    this._subs.forEach(sub => sub.unsubscribe());
    this._subs = [];
    if (!this.navigatingToLobby) {
      this.webSocketService.disconnect();
    }
  }

  private _clearTimers(): void {
    if (this._stepTimer !== null) {
      clearTimeout(this._stepTimer);
      this._stepTimer = null;
    }
    this._clearTypeTimer();
  }

  private _clearTypeTimer(): void {
    if (this._typeTimer !== null) {
      clearInterval(this._typeTimer);
      this._typeTimer = null;
    }
  }
}
