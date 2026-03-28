import { Component, OnDestroy, OnInit, signal, computed, inject, effect } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { RoomApiService } from '../../core/services/room-api.service';
import { ChainDto, RoomEvent, ShowcaseAdvancedEvent } from '../../core/models/events.model';
import { PlayerDto } from '../../core/models/player.model';
import { PLAYER_ICONS } from '../../core/models/player-icons';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  styleUrl: './results.component.scss',
  templateUrl: './results.component.html',
})
export class ResultsComponent implements OnInit, OnDestroy {
  private readonly webSocketService = inject(WebSocketService);
  private readonly playerService = inject(PlayerService);
  private readonly roomApiService = inject(RoomApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  chains = signal<ChainDto[]>([]);
  players = signal<PlayerDto[]>([]);
  currentChainIndex = signal<number>(0);
  revealedEntryCount = signal<number>(0);
  isHost = signal<boolean>(false);
  canProceed = signal<boolean>(false);

  private readonly _allChainsCompleted = signal<boolean>(false);
  readonly allChainsCompleted = this._allChainsCompleted.asReadonly();

  readonly revealIntervalMs: number = environment.showcaseRevealIntervalMs;

  private _intervalId: any = null;
  private _proceedTimer: any = null;
  private _roomCode = '';

  readonly allRevealed = computed(() => {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    return this.revealedEntryCount() >= chain.entries.length;
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
      this.webSocketService.subscribe(`/topic/game/${this._roomCode}`, (msg: unknown) => {
        const event = msg as ShowcaseAdvancedEvent;
        if (event && typeof event.chainIndex === 'number') {
          this.canProceed.set(false);
          this.currentChainIndex.set(event.chainIndex);
          this.revealedEntryCount.set(0);
          this.startRevealInterval();
        }
      });

      this.webSocketService.subscribe(`/topic/room/${this._roomCode}`, (msg: unknown) => {
        const event = msg as RoomEvent;
        if (event?.type === 'GAME_RESET') {
          this.router.navigate(['/lobby', this._roomCode]);
        }
      });
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

  startRevealInterval(): void {
    this._clearInterval();
    this._intervalId = setInterval(() => {
      const chain = this.chains()[this.currentChainIndex()];
      if (!chain) return;
      const max = chain.entries.length;
      this.revealedEntryCount.update(v => v < max ? v + 1 : v);
    }, this.revealIntervalMs);
  }

  navigateToChain(index: number): void {
    const canNavigate = index < this.currentChainIndex() || this.allChainsCompleted();
    if (!canNavigate) return;
    this._clearInterval();
    this.currentChainIndex.set(index);
    this.revealedEntryCount.set(this.chains()[index]?.entries.length ?? 0);
    this.canProceed.set(true);
  }

  onNextChain(): void {
    this.webSocketService.send(`/app/room/${this._roomCode}/next-chain`, {});
  }

  onBackToLobby(): void {
    const token = this.playerService.loadFromLocalStorage(this._roomCode)?.playerToken ?? '';
    this.roomApiService.resetGame(this._roomCode, token).subscribe({
      error: () => this.router.navigate(['/lobby', this._roomCode])
    });
  }

  onExportThread(): void {}

  ngOnDestroy(): void {
    this._clearInterval();
    if (this._proceedTimer) clearTimeout(this._proceedTimer);
    this.webSocketService.disconnect();
  }

  private _clearInterval(): void {
    if (this._intervalId !== null) {
      clearInterval(this._intervalId);
      this._intervalId = null;
    }
  }
}
