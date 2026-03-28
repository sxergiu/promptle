import { Component, OnDestroy, OnInit, signal, computed, effect } from '@angular/core';
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
  template: `
    <div class="round-info">Round {{ currentRound() }} / {{ totalRounds() }}</div>
    <div class="timer">{{ remainingSeconds() }}s</div>
    @if (phase() === GamePhase.PROMPTING) {
      <app-prompting
        [roomCode]="roomCode"
        [submittedCount]="submittedCount()"
        [totalCount]="players().length"
        [hasSubmitted]="hasSubmitted()"
      ></app-prompting>
    }
    @if (phase() === GamePhase.GENERATING) {
      <app-generating></app-generating>
    }
    @if (phase() === GamePhase.GUESSING) {
      <app-guessing
        [roomCode]="roomCode"
        [imageUrl]="imageUrl() ?? ''"
        [submittedCount]="submittedCount()"
        [totalCount]="players().length"
        [hasSubmitted]="hasSubmitted()"
      ></app-guessing>
    }
    @if (!wsConnected()) {
      <div class="reconnecting">Reconnecting...</div>
    }
  `,
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
  wsConnected = signal<boolean>(true);
  hasSubmitted = signal<boolean>(false);

  private _tick = signal(0);
  private _tickInterval: ReturnType<typeof setInterval> | null = null;
  private _pendingChains: any[] = [];
  private _hostId = '';

  readonly remainingSeconds = computed(() => {
    this._tick(); // reactive dependency — re-evaluates every second
    const elapsed = (Date.now() - this.serverTimestamp()) / 1000;
    return Math.max(0, Math.floor(this.timerSeconds() - elapsed));
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
        this.router.navigate(['/game', this.roomCode, 'results'], { state: { chains: this._pendingChains, hostId: this._hostId } });
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
          this.phase.set(phaseEvent.phase);
          this.currentRound.set(phaseEvent.round);
          this.totalRounds.set(phaseEvent.totalRounds);
          this.timerSeconds.set(phaseEvent.timerSeconds);
          this.serverTimestamp.set(phaseEvent.serverTimestamp);
        } else if ('submittedCount' in payload) {
          const subEvent = event as SubmissionUpdateEvent;
          this.submittedCount.set(subEvent.submittedCount);
        } else if ('chains' in payload) {
          this._pendingChains = (payload as any).chains;
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
        }
      });
    }, 5000, () => this.wsConnected.set(false));
  }

  ngOnDestroy(): void {
    if (this._tickInterval !== null) {
      clearInterval(this._tickInterval);
      this._tickInterval = null;
    }
    this.webSocketService.disconnect();
  }
}
