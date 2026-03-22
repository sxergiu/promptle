import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';

import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { PlayerCardComponent } from '../../shared/components/player-card/player-card.component';
import { PlayerDto } from '../../core/models/player.model';
import { RoomEvent } from '../../core/models/events.model';

const DUPLICATE_TAB_TIMEOUT_MS = 50;

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

  playerId = '';
  private playerToken = '';
  roomCode = '';

  private channel: BroadcastChannel | null = null;
  private duplicateCheckTimer: ReturnType<typeof setTimeout> | null = null;

  isHost = computed(() => this.playerId !== '' && this.playerId === this.hostId());

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private roomApiService: RoomApiService,
    private webSocketService: WebSocketService,
    private playerService: PlayerService,
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
  }

  ngOnDestroy(): void {
    if (this.duplicateCheckTimer !== null) {
      clearTimeout(this.duplicateCheckTimer);
    }
    this.channel?.close();
    if (!this.duplicateSession()) {
      this.webSocketService.disconnect();
    }
  }

  private initializeLobby(): void {
    this.roomApiService.getGameStateSnapshot(this.roomCode, this.playerToken).subscribe({
      next: (state) => {
        this.players.set(state.players);
        this.hostId.set(state.hostId);
      },
    });

    this.webSocketService.connect(this.playerToken, this.roomCode, () => {
      this.webSocketService.subscribe(`/topic/room/${this.roomCode}`, (event: unknown) => {
        const roomEvent = event as RoomEvent;
        if (roomEvent.type === 'PLAYER_JOINED' || roomEvent.type === 'PLAYER_LEFT' || roomEvent.type === 'HOST_CHANGED') {
          this.players.set(roomEvent.players);
          this.hostId.set(roomEvent.hostId);
        } else if (roomEvent.type === 'GAME_STARTED') {
          this.router.navigate(['/game', this.roomCode]);
        }
      });
    }, 0); // no auto-reconnect in lobby
  }

  inviteOthers(): void {
    navigator.clipboard.writeText(`${window.location.origin}/join/${this.roomCode}`);
  }

  startGame(): void {
    this.roomApiService.startGame(this.roomCode, this.playerToken).subscribe();
  }

  exitLobby(): void {
    this.playerService.clearLocalStorage(this.roomCode);
    this.router.navigate(['/']);
  }
}
