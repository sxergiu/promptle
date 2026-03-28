import { Component, OnDestroy, OnInit, signal, computed, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { ChainDto, ShowcaseAdvancedEvent } from '../../core/models/events.model';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [MatButtonModule],
  template: `
    <div>
      @for (chain of chains(); track $index) {
        @if ($index === currentChainIndex()) {
          @for (entry of chain.entries; track $index; let i = $index) {
            @if (i < revealedEntryCount()) {
              <div>
                <span>{{ entry.text }}</span>
                @if (entry.imageUrl) {
                  <img [src]="entry.imageUrl" alt="entry image" />
                }
              </div>
            }
          }
        }
      }
      <button
        mat-raised-button
        [disabled]="!isHost() || !allRevealed()"
        (click)="currentChainIndex() < chains().length - 1 ? onNextChain() : onBackToLobby()"
      >
        {{ currentChainIndex() < chains().length - 1 ? 'Next' : 'Back to Lobby' }}
      </button>
      <button mat-button (click)="onExportThread()">Export Thread</button>
    </div>
  `,
})
export class ResultsComponent implements OnInit, OnDestroy {
  private readonly webSocketService = inject(WebSocketService);
  private readonly playerService = inject(PlayerService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  chains = signal<ChainDto[]>([]);
  currentChainIndex = signal<number>(0);
  revealedEntryCount = signal<number>(0);
  isHost = signal<boolean>(false);

  readonly revealIntervalMs: number = environment.showcaseRevealIntervalMs;

  private _intervalId: any = null;
  private _roomCode = '';

  readonly allRevealed = computed(() => {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    return this.revealedEntryCount() >= chain.entries.length;
  });

  ngOnInit(): void {
    this._roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';

    // Read chains from navigation state (passed by game shell).
    // window.history.state is used because getCurrentNavigation() returns null
    // in ngOnInit — navigation has already completed by the time the component initializes.
    const navState = window.history.state as { chains?: ChainDto[]; hostId?: string };
    if (navState?.chains?.length) {
      this.chains.set(navState.chains);
    }

    const stateHostId = navState?.hostId;
    const stored = this.playerService.loadFromLocalStorage(this._roomCode);
    const token = stored?.playerToken ?? '';
    if (stateHostId && stored?.playerId && stateHostId === stored.playerId) {
      this.isHost.set(true);
    }

    this.webSocketService.connect(token, this._roomCode, () => {
      this.webSocketService.subscribe(`/topic/game/${this._roomCode}`, (msg: unknown) => {
        const event = msg as ShowcaseAdvancedEvent;
        if (event && typeof event.chainIndex === 'number') {
          this.currentChainIndex.set(event.chainIndex);
          this.revealedEntryCount.set(0);
          this.startRevealInterval();
        }
      });
    });

    this.startRevealInterval();
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

  onNextChain(): void {
    this.webSocketService.send(`/app/room/${this._roomCode}/next-chain`, {});
  }

  onBackToLobby(): void {
    this.playerService.clearLocalStorage(this._roomCode);
    this.router.navigate(['/lobby', this._roomCode]);
  }

  onExportThread(): void {}

  ngOnDestroy(): void {
    this._clearInterval();
  }

  private _clearInterval(): void {
    if (this._intervalId !== null) {
      clearInterval(this._intervalId);
      this._intervalId = null;
    }
  }
}
