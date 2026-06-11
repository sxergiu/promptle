import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';

import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { SoundService } from '../../core/services/sound.service';
import { PlayerCardComponent } from '../../shared/components/player-card/player-card.component';
import { PlayerDto } from '../../core/models/player.model';
import { GameStateSnapshot, RoomEvent } from '../../core/models/events.model';
import { GamePhase } from '../../core/models/game-phase.enum';
import { StompSubscription } from '@stomp/stompjs';

const DUPLICATE_TAB_TIMEOUT_MS = 50;
// Self-healing roster reconciliation. The lobby roster updates live from room
// broadcasts, but if a single PLAYER_JOINED/RETURNED event is ever missed (a
// transient WS hiccup on a host whose tab stays foregrounded), the returning
// player would stay invisible forever. Re-fetching the snapshot on a slow timer
// converges any such desync within seconds, on both the host and the newcomer.
const RECONCILE_INTERVAL_MS = 8000;

@Component({
  selector: 'app-lobby',
  standalone: true,
  imports: [CommonModule, PlayerCardComponent],
  templateUrl: './lobby.component.html',
  styleUrl: './lobby.component.scss',
})
export class LobbyComponent implements OnInit, OnDestroy {
  players = signal<PlayerDto[]>([]);
  hostId = signal<string>('');
  duplicateSession = signal(false);
  // True while the previous game's results are still being viewed by some players.
  // The room can't start a new game until everyone has returned, so Start is held.
  waitingForResults = signal(false);

  playerId = '';
  private playerToken = '';
  roomCode = '';
  private navigatingToGame = false;

  private channel: BroadcastChannel | null = null;
  private duplicateCheckTimer: ReturnType<typeof setTimeout> | null = null;
  private reconcileTimer: ReturnType<typeof setInterval> | null = null;
  private _roomSub: StompSubscription | null = null;
  private initialized = false;
  private destroyed = false;
  // Self-heal: if a GAME_RESET is ever missed, the lobby would stay stuck showing
  // "waiting for results" with Start disabled. Re-fetching the snapshot whenever the
  // tab regains focus recovers from any such missed event.
  private readonly onVisible = () => {
    if (document.visibilityState === 'visible' && this.initialized) {
      this.refreshSnapshot();
    }
  };

  isHost = computed(() => this.playerId !== '' && this.playerId === this.hostId());
  emptySlots = computed(() => Array.from({ length: Math.max(0, 8 - this.players().length) }));

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private roomApiService: RoomApiService,
    private webSocketService: WebSocketService,
    private playerService: PlayerService,
    private sound: SoundService,
  ) {}

  ngOnInit(): void {
    this.roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';

    const stored = this.playerService.loadFromLocalStorage(this.roomCode);
    if (stored) {
      this.playerToken = stored.playerToken;
      this.playerId = stored.playerId;
    }

    this.channel = new BroadcastChannel(`promptle_lobby_${this.roomCode}`);

    this.channel.onmessage = (event: MessageEvent) => {
      if (event.data?.type === 'PING') {
        this.channel!.postMessage({ type: 'PONG' });
      } else if (event.data?.type === 'PONG') {
        if (this.duplicateCheckTimer !== null) {
          clearTimeout(this.duplicateCheckTimer);
          this.duplicateCheckTimer = null;
        }
        this.duplicateSession.set(true);
      }
    };

    this.channel.postMessage({ type: 'PING' });

    this.duplicateCheckTimer = setTimeout(() => {
      this.duplicateCheckTimer = null;
      if (!this.duplicateSession()) {
        this.initializeLobby();
      }
    }, DUPLICATE_TAB_TIMEOUT_MS);

    document.addEventListener('visibilitychange', this.onVisible);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    document.removeEventListener('visibilitychange', this.onVisible);
    this.stopReconcilePoll();
    if (this.duplicateCheckTimer !== null) {
      clearTimeout(this.duplicateCheckTimer);
    }
    this.channel?.close();
    this._roomSub?.unsubscribe();
    if (!this.duplicateSession() && !this.navigatingToGame) {
      this.webSocketService.disconnect();
    }
  }

  private initializeLobby(): void {
    this.initialized = true;
    this.refreshSnapshot();
    this.startReconcilePoll();

    // Reuses the live connection handed off from the results showcase when present;
    // otherwise (direct navigation / refresh) opens a fresh one.
    this.webSocketService.connect(this.playerToken, this.roomCode, () => {
      // The mid-game auto-join in applySnapshot can destroy this component before
      // the WS handshake completes — don't subscribe on behalf of a dead lobby.
      if (this.destroyed) return;
      this._roomSub?.unsubscribe();
      this._roomSub = this.webSocketService.subscribe(`/topic/room/${this.roomCode}`, (event: unknown) => {
        const roomEvent = event as RoomEvent;
        if (roomEvent.type === 'PLAYER_JOINED' || roomEvent.type === 'PLAYER_LEFT' || roomEvent.type === 'HOST_CHANGED' || roomEvent.type === 'PLAYER_RETURNED') {
          // Roster deltas only fire here (post-handshake), so this never sounds for the
          // local player's own join — that broadcast precedes this subscription.
          if (roomEvent.type === 'PLAYER_JOINED') this.sound.playerJoined();
          else if (roomEvent.type === 'PLAYER_LEFT') this.sound.playerLeft();
          this.players.set(roomEvent.players);
          this.hostId.set(roomEvent.hostId);
        } else if (roomEvent.type === 'GAME_RESET') {
          // Everyone has returned from the showcase — the room is now a fresh lobby.
          this.players.set(roomEvent.players);
          this.hostId.set(roomEvent.hostId);
          this.waitingForResults.set(false);
        } else if (roomEvent.type === 'GAME_STARTED') {
          // startGame() may have already navigated this client on its REST success.
          if (this.navigatingToGame) return;
          this.sound.gameStarted();
          this.navigatingToGame = true;
          this._roomSub?.unsubscribe();
          this.router.navigate(['/game', this.roomCode]);
        }
      });

      // Re-fetch after subscribing to catch any events missed during the handshake.
      // This callback re-runs on every (re)connect, so a socket that dropped while the
      // tab was backgrounded re-subscribes and re-syncs once the phone comes back.
      this.refreshSnapshot();
    }, 5000);
  }

  private startReconcilePoll(): void {
    if (this.reconcileTimer !== null) return;
    this.reconcileTimer = setInterval(() => {
      // Only reconcile while the tab is foregrounded — backgrounded tabs already
      // re-sync on the visibilitychange handler when they return, and polling a
      // hidden tab is wasted work.
      if (document.visibilityState === 'visible' && !this.navigatingToGame) {
        this.refreshSnapshot();
      }
    }, RECONCILE_INTERVAL_MS);
  }

  private stopReconcilePoll(): void {
    if (this.reconcileTimer !== null) {
      clearInterval(this.reconcileTimer);
      this.reconcileTimer = null;
    }
  }

  private refreshSnapshot(): void {
    this.roomApiService.getGameStateSnapshot(this.roomCode, this.playerToken).subscribe({
      next: (state) => this.applySnapshot(state),
      error: (err: unknown) => {
        // Invalid token / room gone (4xx) — the only kick-home case. Transient
        // network failures (status 0, e.g. a phone resuming with the radio still
        // down) and server errors must not destroy the session; the next
        // visibilitychange refetch recovers instead.
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        if (status >= 400 && status < 500) {
          this.playerService.clearLocalStorage(this.roomCode);
          this.router.navigate(['/']);
        }
      },
    });
  }

  private applySnapshot(state: GameStateSnapshot): void {
    this.players.set(state.players);
    this.hostId.set(state.hostId);

    if (state.phase === GamePhase.RESULTS) {
      // Returned to the lobby ahead of others still viewing results — wait for them.
      this.waitingForResults.set(true);
    } else if (state.phase !== GamePhase.LOBBY) {
      // A game is already underway (e.g. GAME_STARTED was missed) — auto-join it.
      this.navigatingToGame = true;
      this._roomSub?.unsubscribe();
      this.router.navigate(['/game', this.roomCode]);
    } else {
      this.waitingForResults.set(false);
    }
  }

  /** Fresh lobby → everyone is "returned"; during a results wait, only flagged players are. */
  isPlayerReturned(p: PlayerDto): boolean {
    return !this.waitingForResults() || p.returnedToLobby === true;
  }

  // -- Actions --

  copied = signal(false);

  inviteOthers(): void {
    const url = `${window.location.origin}/join/${this.roomCode}`;
    if (navigator.share) {
      navigator.share({ title: 'Join Promptle', url }).catch(() => {});
      return;
    }
    const textarea = document.createElement('textarea');
    textarea.value = url;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    if (!ok && navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(url).catch(() => window.prompt('Copy this link:', url));
    } else if (!ok) {
      window.prompt('Copy this link:', url);
    }
    this.copied.set(true);
    setTimeout(() => this.copied.set(false), 2000);
  }

  starting = signal(false);

  startGame(): void {
    if (this.starting()) return;
    this.starting.set(true);
    this.roomApiService.startGame(this.roomCode, this.playerToken).subscribe({
      // Navigate as soon as the start succeeds rather than waiting for the GAME_STARTED
      // broadcast — a host whose socket silently dropped (mobile backgrounding) would
      // otherwise never receive it and stay locked here while everyone else plays. The
      // game component fetches its own snapshot on load, so a direct nav is safe. The
      // navigatingToGame guard keeps the later broadcast from double-navigating.
      next: () => {
        if (this.navigatingToGame) return;
        this.navigatingToGame = true;
        this._roomSub?.unsubscribe();
        this.router.navigate(['/game', this.roomCode]);
      },
      error: () => this.starting.set(false),
    });
  }

  exitLobby(): void {
    this.playerService.clearLocalStorage(this.roomCode);
    this.router.navigate(['/']);
  }
}
