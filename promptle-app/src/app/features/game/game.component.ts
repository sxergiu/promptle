import { Component, OnDestroy, OnInit, signal, computed, effect } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GamePhase } from '../../core/models/game-phase.enum';
import { PromptingPhaseComponent } from './prompting/prompting.component';
import { GeneratingComponent } from './generating/generating.component';
import { GuessingPhaseComponent } from './guessing/guessing.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { SoundService } from '../../core/services/sound.service';
import { PhaseChangedEvent, SubmissionUpdateEvent, RoundReadyPayload, GameResultsEvent, RoomEvent } from '../../core/models/events.model';
import { PlayerDto } from '../../core/models/player.model';

@Component({
  selector: 'app-game',
  standalone: true,
  imports: [PromptingPhaseComponent, GeneratingComponent, GuessingPhaseComponent],
  styleUrl: './game.component.scss',
  templateUrl: './game.component.html',
})
export class GameComponent implements OnInit, OnDestroy {
  readonly GamePhase = GamePhase;

  phase = signal<GamePhase>(GamePhase.LOBBY);
  currentRound = signal<number>(0);
  totalRounds = signal<number>(0);
  timerSeconds = signal<number>(0);
  serverTimestamp = signal<number>(0);
  imageUrl = signal<string | null>(null);
  players = signal<PlayerDto[]>([]);
  submittedCount = signal<number>(0);
  totalPlayerCount = signal<number>(0);
  wsConnected = signal<boolean>(true);
  hasSubmitted = signal<boolean>(false);

  private _tick = signal(0);
  private _tickInterval: ReturnType<typeof setInterval> | null = null;
  private _pendingChains: any[] = [];
  private _hostId = '';
  // Set to true once any WS phase event has been applied. Used to prevent a
  // stale HTTP snapshot from overwriting live WS state when both arrive concurrently.
  private _wsPhaseReceived = false;
  // "phase#round" of the last applied phase, so re-delivered events (reconnect,
  // duplicate broadcast) don't wipe hasSubmitted/submittedCount mid-round.
  private _lastPhaseKey = '';

  readonly remainingSeconds = computed(() => {
    this._tick(); // reactive dependency — re-evaluates every second
    const elapsed = (Date.now() - this.serverTimestamp()) / 1000;
    return Math.max(0, Math.floor(this.timerSeconds() - elapsed));
  });

  readonly timerPercent = computed(() => {
    const total = this.timerSeconds();
    if (total <= 0) return 0;
    return (this.remainingSeconds() / total) * 100;
  });

  // One entry per player for the bottom readiness strip — true once that slot
  // has submitted (green pip), false while we're still waiting (red pip).
  readonly readyPips = computed(() =>
    Array.from({ length: this.totalPlayerCount() }, (_, i) => i < this.submittedCount())
  );

  roomCode = '';
  private playerToken = '';

  // Last whole-second value of remainingSeconds seen by the timer-cue effect, so
  // warnings fire once on the downward crossing and never on a round's timer reset.
  private _lastRemaining = Infinity;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private roomApiService: RoomApiService,
    private webSocketService: WebSocketService,
    private playerService: PlayerService,
    private sound: SoundService,
  ) {
    effect(() => {
      if (this.phase() === GamePhase.RESULTS) {
        this.router.navigate(['/game', this.roomCode, 'results'], { state: { chains: this._pendingChains, hostId: this._hostId, players: this.players() } });
      }
    });

    // Timer cues: fire only on a live downward tick during a timed phase, so a
    // round's fresh timer (remaining jumps up) and hydration never trigger them.
    effect(() => {
      const r = this.remainingSeconds();
      const timed = this.phase() === GamePhase.PROMPTING || this.phase() === GamePhase.GUESSING;
      if (timed && r < this._lastRemaining) {
        if (r === 10 || r === 5) this.sound.timerWarning();
        else if (r === 3) this.sound.timerFinal();
      }
      this._lastRemaining = r;
    });
  }

  ngOnInit(): void {
    this._tickInterval = setInterval(() => this._tick.update(v => v + 1), 1000);

    this.roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';

    const stored = this.playerService.loadFromLocalStorage(this.roomCode);
    if (stored) {
      this.playerToken = stored.playerToken;
    }

    this._fetchSnapshot();

    this.webSocketService.connect(this.playerToken, this.roomCode, () => {
      const wasDisconnected = !this.wsConnected();
      this.wsConnected.set(true);

      if (wasDisconnected && this._wsPhaseReceived) {
        this._wsPhaseReceived = false;
        this._fetchSnapshot();
      }

      this.webSocketService.subscribe(`/topic/game/${this.roomCode}`, (event: unknown) => {
        const payload = event as Record<string, unknown>;
        if ('phase' in payload) {
          const phaseEvent = event as PhaseChangedEvent;
          if (phaseEvent.phase === GamePhase.RESULTS) return; // wait for GameResultsEvent which carries chains

          this._wsPhaseReceived = true;

          if (phaseEvent.phase === GamePhase.GENERATING) {
            this.imageUrl.set(null);
          }

          if (phaseEvent.phase === GamePhase.GUESSING) {
            // Apply the phase immediately — don't buffer waiting for RoundReadyPayload.
            // Fetch imageUrl from the HTTP snapshot instead (reliable, no user-queue dependency).
            this._applyPhaseEvent(phaseEvent);
            this._fetchImageUrl();
            return;
          }

          this._applyPhaseEvent(phaseEvent);
        } else if ('submittedCount' in payload) {
          const subEvent = event as SubmissionUpdateEvent;
          this.submittedCount.set(subEvent.submittedCount);
          this.totalPlayerCount.set((subEvent as any).totalCount ?? this.totalPlayerCount());
        } else if ('chains' in payload) {
          this._pendingChains = (payload as any).chains;
          this._enterResults();
        }
      });

      this.webSocketService.subscribe('/user/queue/game', (event: unknown) => {
        const payload = event as Record<string, unknown>;
        if ('chains' in payload) {
          const resultsEvent = event as GameResultsEvent;
          this._pendingChains = resultsEvent.chains;
          this._enterResults();
        } else {
          // RoundReadyPayload — still accept it if it arrives (updates imageUrl faster than HTTP).
          const roundReady = event as RoundReadyPayload;
          this.imageUrl.set(roundReady.imageUrl);
          this.sound.imageReady();
        }
      });

      // Track host changes during the game (e.g. the host's WS drops during a long GENERATING
      // phase and the server reassigns host). Without this, _hostId stays stale and the wrong
      // hostId is handed to the results screen — leaving the showcase with no one able to advance.
      this.webSocketService.subscribe(`/topic/room/${this.roomCode}`, (event: unknown) => {
        const roomEvent = event as RoomEvent;
        if (roomEvent?.hostId) this._hostId = roomEvent.hostId;
        if (roomEvent?.players) this.players.set(roomEvent.players);
      });
    }, 5000, () => this.wsConnected.set(false));
  }

  private _fetchSnapshot(): void {
    this.roomApiService.getGameStateSnapshot(this.roomCode, this.playerToken).subscribe({
      next: (snapshot) => {
        this.totalRounds.set(snapshot.totalRounds);
        this.players.set(snapshot.players);
        this.totalPlayerCount.set(snapshot.totalRounds || snapshot.players.length);
        this._hostId = snapshot.hostId ?? '';

        if (!this._wsPhaseReceived) {
          this.phase.set(snapshot.phase);
          this.currentRound.set(snapshot.currentRound);
          this.timerSeconds.set(snapshot.timerSeconds);
          this.serverTimestamp.set(snapshot.serverTimestamp);
          this.imageUrl.set(snapshot.imageUrl);
          this.hasSubmitted.set(snapshot.hasSubmitted);
          this.submittedCount.set(snapshot.submittedCount);
          this._lastPhaseKey = snapshot.phase + '#' + snapshot.currentRound;
        } else if (snapshot.phase + '#' + snapshot.currentRound === this._lastPhaseKey) {
          // WS won the race for the same phase/round — the snapshot is still
          // authoritative for this player's submission state in that round.
          this.hasSubmitted.set(snapshot.hasSubmitted);
        }
      },
    });
  }

  /** Fetches only the imageUrl from the snapshot for the current GUESSING round. */
  private _fetchImageUrl(): void {
    this.roomApiService.getGameStateSnapshot(this.roomCode, this.playerToken).subscribe({
      next: (snapshot) => {
        if (snapshot.phase === GamePhase.GUESSING && snapshot.imageUrl) {
          this.imageUrl.set(snapshot.imageUrl);
        }
      },
    });
  }

  private _applyPhaseEvent(e: PhaseChangedEvent): void {
    const key = e.phase + '#' + e.round;
    if (key === this._lastPhaseKey) {
      // Re-delivery of the current phase (reconnect / duplicate broadcast):
      // refresh the timer base but don't reset submission state — a reset
      // would wipe the child form via ngOnChanges.
      this.timerSeconds.set(e.timerSeconds);
      this.serverTimestamp.set(e.serverTimestamp);
      this.totalRounds.set(e.totalRounds);
      return;
    }
    this._lastPhaseKey = key;
    this.phase.set(e.phase);
    this.currentRound.set(e.round);
    this.totalRounds.set(e.totalRounds);
    this.timerSeconds.set(e.timerSeconds);
    this.serverTimestamp.set(e.serverTimestamp);
    this.hasSubmitted.set(false);
    this.submittedCount.set(0);

    // Phase-entry cue. Fires only here — i.e. only on a live WS phase change, never
    // on the HTTP-snapshot hydration path (which sets `phase` directly).
    if (e.phase === GamePhase.PROMPTING || e.phase === GamePhase.GUESSING) {
      this.sound.roundStart();
    } else if (e.phase === GamePhase.GENERATING) {
      this.sound.phaseGenerating();
    }
  }

  /** Enter RESULTS from a live event, firing the reveal cue once (shell owns it). */
  private _enterResults(): void {
    if (this.phase() === GamePhase.RESULTS) return; // both RESULTS events arrived — don't double-fire
    this.sound.resultsReveal();
    this.phase.set(GamePhase.RESULTS);
  }

  ngOnDestroy(): void {
    if (this._tickInterval !== null) {
      clearInterval(this._tickInterval);
      this._tickInterval = null;
    }
    if (this.phase() !== GamePhase.RESULTS) {
      this.webSocketService.disconnect();
    }
  }
}
