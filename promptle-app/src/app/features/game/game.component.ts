import { Component, OnDestroy, OnInit, signal, computed, effect, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { GamePhase } from '../../core/models/game-phase.enum';
import { PromptingPhaseComponent } from './prompting/prompting.component';
import { GeneratingComponent } from './generating/generating.component';
import { GuessingPhaseComponent } from './guessing/guessing.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { PhaseChangedEvent, SubmissionUpdateEvent, RoundReadyPayload, GameResultsEvent } from '../../core/models/events.model';
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
  // Buffered GUESSING phase event — held until the imageUrl payload arrives so the
  // guessing screen never renders with a "Loading image…" placeholder.
  private _pendingGuessingEvent: PhaseChangedEvent | null = null;

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

  roomCode = '';
  private playerToken = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private roomApiService: RoomApiService,
    private webSocketService: WebSocketService,
    private playerService: PlayerService,
  ) {
    effect(() => {
      if (this.phase() === GamePhase.RESULTS) {
        this.router.navigate(['/game', this.roomCode, 'results'], { state: { chains: this._pendingChains, hostId: this._hostId, players: this.players() } });
      }
    });
  }

  ngOnInit(): void {
    this._tickInterval = setInterval(() => this._tick.update(v => v + 1), 1000);

    this.roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';

    const stored = this.playerService.loadFromLocalStorage(this.roomCode);
    if (stored) {
      this.playerToken = stored.playerToken;
    }

    this.roomApiService.getGameStateSnapshot(this.roomCode, this.playerToken).subscribe({
      next: (snapshot) => {
        this.phase.set(snapshot.phase);
        this.currentRound.set(snapshot.currentRound);
        this.totalRounds.set(snapshot.totalRounds);
        this.timerSeconds.set(snapshot.timerSeconds);
        this.serverTimestamp.set(snapshot.serverTimestamp);
        this.imageUrl.set(snapshot.imageUrl);
        this.players.set(snapshot.players);
        // Use totalRounds (= player count set at game-start) rather than snapshot.players.length,
        // which may be lower if not all WS handshakes completed before the snapshot was fetched.
        this.totalPlayerCount.set(snapshot.totalRounds || snapshot.players.length);
        this.hasSubmitted.set(snapshot.hasSubmitted);
        this.submittedCount.set(snapshot.submittedCount);
        this._hostId = snapshot.hostId ?? '';
      },
    });

    this.webSocketService.connect(this.playerToken, this.roomCode, () => {
      this.wsConnected.set(true);
      this.webSocketService.subscribe(`/topic/game/${this.roomCode}`, (event: unknown) => {
        const payload = event as Record<string, unknown>;
        if ('phase' in payload) {
          const phaseEvent = event as PhaseChangedEvent;
          if (phaseEvent.phase === GamePhase.RESULTS) return; // wait for GameResultsEvent which carries chains

          if (phaseEvent.phase === GamePhase.GUESSING) {
            // Buffer: the imageUrl arrives in a separate /user/queue/game message sent
            // right after this broadcast. Delay the phase flip until it lands so the
            // guessing screen never renders with a "Loading image…" placeholder.
            this._pendingGuessingEvent = phaseEvent;
            // Edge case: imageUrl already arrived (rare network reordering)
            if (this.imageUrl() !== null) {
              this._flushPendingGuessing();
            }
            return;
          }

          // Entering GENERATING clears the stale imageUrl from the previous round
          // so the buffer check above works correctly on round 2+.
          if (phaseEvent.phase === GamePhase.GENERATING) {
            this.imageUrl.set(null);
          }

          this._applyPhaseEvent(phaseEvent);
        } else if ('submittedCount' in payload) {
          const subEvent = event as SubmissionUpdateEvent;
          this.submittedCount.set(subEvent.submittedCount);
          this.totalPlayerCount.set((subEvent as any).totalCount ?? this.totalPlayerCount());
        } else if ('chains' in payload) {
          this._pendingChains = (payload as any).chains;
          this.phase.set(GamePhase.RESULTS); // triggers effect → navigate with chains already set
        }
      });

      this.webSocketService.subscribe('/user/queue/game', (event: unknown) => {
        const payload = event as Record<string, unknown>;
        if ('chains' in payload) {
          const resultsEvent = event as GameResultsEvent;
          this._pendingChains = resultsEvent.chains;
          this.phase.set(GamePhase.RESULTS);
        } else {
          const roundReady = event as RoundReadyPayload;
          this.imageUrl.set(roundReady.imageUrl);
          // Flush the buffered GUESSING transition now that the image URL is ready.
          if (this._pendingGuessingEvent) {
            this._flushPendingGuessing();
          }
        }
      });
    }, 5000, () => this.wsConnected.set(false));
  }

  private _applyPhaseEvent(e: PhaseChangedEvent): void {
    this.phase.set(e.phase);
    this.currentRound.set(e.round);
    this.totalRounds.set(e.totalRounds);
    this.timerSeconds.set(e.timerSeconds);
    this.serverTimestamp.set(e.serverTimestamp);
    this.hasSubmitted.set(false);
    this.submittedCount.set(0);
  }

  private _flushPendingGuessing(): void {
    const e = this._pendingGuessingEvent!;
    this._pendingGuessingEvent = null;
    this._applyPhaseEvent(e);
  }

  ngOnDestroy(): void {
    if (this._tickInterval !== null) {
      clearInterval(this._tickInterval);
      this._tickInterval = null;
    }
    // Do not disconnect when transitioning to the results child route —
    // ResultsComponent reuses the same live connection for showcase events.
    if (this.phase() !== GamePhase.RESULTS) {
      this.webSocketService.disconnect();
    }
  }
}
