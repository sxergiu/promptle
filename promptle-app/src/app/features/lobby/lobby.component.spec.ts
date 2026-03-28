import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';

import { LobbyComponent } from './lobby.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { GamePhase } from '../../core/models/game-phase.enum';

describe('LobbyComponent', () => {
  let component: LobbyComponent;
  let fixture: ComponentFixture<LobbyComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const ROOM_CODE = 'LOBBY123';
  const PLAYER_TOKEN = 'player-tok-abc';
  const PLAYER_ID = 'player-id-xyz';

  let wsSubscriptionCallbacks: { [dest: string]: Function } = {};

  beforeEach(async () => {
    wsSubscriptionCallbacks = {};

    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['getGameStateSnapshot', 'startGame']);
    wsSpy = jasmine.createSpyObj('WebSocketService', ['connect', 'disconnect', 'subscribe', 'send']);
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['loadFromLocalStorage']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    playerServiceSpy.loadFromLocalStorage.and.returnValue({
      playerToken: PLAYER_TOKEN,
      playerId: PLAYER_ID,
      alias: 'Alice',
      avatarId: 'icon-1',
    });

    const mockSnapshot = {
      roomCode: ROOM_CODE,
      phase: GamePhase.LOBBY,
      currentRound: 0,
      totalRounds: 0,
      players: [
        { id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true },
        { id: 'player-2', alias: 'Bob', avatarId: 'icon-2', connected: true },
      ],
      hostId: PLAYER_ID,
    };
    roomApiSpy.getGameStateSnapshot = jasmine.createSpy('getGameStateSnapshot').and.returnValue(of(mockSnapshot));

    wsSpy.connect.and.callFake((_token: string, _roomCode: string, onConnect?: () => void) => {
      onConnect?.();
    });

    wsSpy.subscribe.and.callFake((dest: string, cb: Function) => {
      wsSubscriptionCallbacks[dest] = cb;
      return { id: dest, unsubscribe: () => {} };
    });

    await TestBed.configureTestingModule({
      imports: [LobbyComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: RoomApiService, useValue: roomApiSpy },
        { provide: WebSocketService, useValue: wsSpy },
        { provide: PlayerService, useValue: playerServiceSpy },
        { provide: Router, useValue: routerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            params: of({ roomCode: ROOM_CODE }),
            snapshot: {
              paramMap: { get: (k: string) => k === 'roomCode' ? ROOM_CODE : null },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LobbyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await new Promise<void>(resolve => setTimeout(resolve, 60));
  });

  // ---- On mount ----

  it('reads from localStorage via PlayerService on init', () => {
    expect(playerServiceSpy.loadFromLocalStorage).toHaveBeenCalledWith(ROOM_CODE);
  });

  it('connects WebSocket with token and roomCode', () => {
    expect(wsSpy.connect).toHaveBeenCalledWith(PLAYER_TOKEN, ROOM_CODE, jasmine.any(Function), jasmine.any(Number));
  });

  it('subscribes to /topic/room/{roomCode}', () => {
    expect(wsSpy.subscribe).toHaveBeenCalledWith(
      `/topic/room/${ROOM_CODE}`,
      jasmine.any(Function)
    );
  });

  // ---- WS event handling ----

  it('PLAYER_JOINED event updates players signal', () => {
    const newPlayer = { id: 'player-3', alias: 'Carol', avatarId: 'icon-3', connected: true };
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'PLAYER_JOINED',
      players: [
        { id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true },
        { id: 'player-2', alias: 'Bob', avatarId: 'icon-2', connected: true },
        newPlayer,
      ],
      hostId: PLAYER_ID,
    });
    fixture.detectChanges();

    expect(component.players().length).toBe(3);
  });

  it('PLAYER_LEFT event removes player from players signal', () => {
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'PLAYER_LEFT',
      players: [{ id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true }],
      hostId: PLAYER_ID,
    });
    fixture.detectChanges();

    expect(component.players().length).toBe(1);
  });

  it('HOST_CHANGED event updates hostId signal', () => {
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'HOST_CHANGED',
      players: component.players(),
      hostId: 'player-2',
    });
    fixture.detectChanges();

    expect(component.hostId()).toBe('player-2');
  });

  it('GAME_STARTED event navigates to /game/{roomCode}', () => {
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'GAME_STARTED',
      players: [],
      hostId: PLAYER_ID,
    });

    expect(routerSpy.navigate).toHaveBeenCalledWith(['/game', ROOM_CODE]);
  });

  // ---- Template ----

  it('renders one PlayerCardComponent per player', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const playerCards = compiled.querySelectorAll('app-player-card');
    expect(playerCards.length).toBe(component.players().length);
  });

  it('Invite Others button visible only to host', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const inviteBtn = buttons.find(b => b.textContent?.toLowerCase().includes('invite'));

    if (component.isHost()) {
      expect(inviteBtn).toBeTruthy();
    } else {
      expect(inviteBtn).toBeFalsy();
    }
  });

  it('Start Game button is disabled when fewer than 2 players', () => {
    component.players.set([
      { id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true },
    ]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const startBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('start')
    ) as HTMLButtonElement | undefined;

    if (startBtn) {
      expect(startBtn.disabled).toBeTrue();
    } else {
      expect(component.players().length).toBeLessThan(2);
    }
  });

  it('Start Game button is disabled for non-host', () => {
    component.hostId.set('another-player-id');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const startBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('start')
    ) as HTMLButtonElement | undefined;

    if (startBtn) {
      expect(startBtn.disabled).toBeTrue();
    } else {
      expect(component.isHost()).toBeFalse();
    }
  });

  // ---- On destroy ----

  it('disconnects WebSocket on destroy', () => {
    component.ngOnDestroy();
    expect(wsSpy.disconnect).toHaveBeenCalled();
  });
});
