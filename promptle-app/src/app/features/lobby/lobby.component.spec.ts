import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { LobbyComponent } from './lobby.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { SoundService } from '../../core/services/sound.service';
import { GamePhase } from '../../core/models/game-phase.enum';

describe('LobbyComponent', () => {
  let component: LobbyComponent;
  let fixture: ComponentFixture<LobbyComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;
  let soundSpy: jasmine.SpyObj<SoundService>;

  const ROOM_CODE = 'LOBBY123';
  const PLAYER_TOKEN = 'player-tok-abc';
  const PLAYER_ID = 'player-id-xyz';

  let wsSubscriptionCallbacks: { [dest: string]: Function } = {};

  beforeEach(async () => {
    wsSubscriptionCallbacks = {};

    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['getGameStateSnapshot', 'startGame']);
    wsSpy = jasmine.createSpyObj('WebSocketService', ['connect', 'disconnect', 'subscribe', 'send']);
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['loadFromLocalStorage', 'clearLocalStorage']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);
    soundSpy = jasmine.createSpyObj('SoundService', ['playerJoined', 'playerLeft', 'gameStarted']);

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
        { provide: SoundService, useValue: soundSpy },
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
    expect(soundSpy.playerJoined).toHaveBeenCalledTimes(1);
    expect(soundSpy.playerLeft).not.toHaveBeenCalled();
  });

  it('PLAYER_LEFT event removes player from players signal', () => {
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'PLAYER_LEFT',
      players: [{ id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true }],
      hostId: PLAYER_ID,
    });
    fixture.detectChanges();

    expect(component.players().length).toBe(1);
    expect(soundSpy.playerLeft).toHaveBeenCalledTimes(1);
    expect(soundSpy.playerJoined).not.toHaveBeenCalled();
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
    expect(soundSpy.gameStarted).toHaveBeenCalledTimes(1);
  });

  it('startGame navigates to /game on success without waiting for the broadcast', () => {
    roomApiSpy.startGame.and.returnValue(of(undefined));

    component.startGame();

    expect(roomApiSpy.startGame).toHaveBeenCalledWith(ROOM_CODE, PLAYER_TOKEN);
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/game', ROOM_CODE]);
  });

  it('startGame does not double-navigate when GAME_STARTED also arrives', () => {
    roomApiSpy.startGame.and.returnValue(of(undefined));

    component.startGame();
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'GAME_STARTED',
      players: [],
      hostId: PLAYER_ID,
    });

    expect(routerSpy.navigate).toHaveBeenCalledTimes(1);
  });

  it('HOST_CHANGED event fires no join/leave cue', () => {
    wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
      type: 'HOST_CHANGED',
      players: component.players(),
      hostId: 'player-2',
    });

    expect(soundSpy.playerJoined).not.toHaveBeenCalled();
    expect(soundSpy.playerLeft).not.toHaveBeenCalled();
  });

  // ---- Template ----

  it('renders a card for each player plus empty filler slots', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const playerCards = compiled.querySelectorAll('app-player-card');
    expect(playerCards.length).toBe(component.players().length + component.emptySlots().length);
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

  it('Start Game button is disabled when there are no players', () => {
    component.players.set([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const startBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('start')
    ) as HTMLButtonElement | undefined;

    if (startBtn) {
      expect(startBtn.disabled).toBeTrue();
    } else {
      expect(component.players().length).toBeLessThan(1);
    }
  });

  it('Start Game button is disabled while waiting for players to finish results', () => {
    component.players.set([
      { id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true },
      { id: 'player-2', alias: 'Bob', avatarId: 'icon-2', connected: true },
    ]);
    component.waitingForResults.set(true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const startBtn = Array.from(compiled.querySelectorAll('button')).find(b =>
      b.textContent?.toLowerCase().includes('start')
    ) as HTMLButtonElement | undefined;

    expect(startBtn?.disabled).toBeTrue();
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

  // ---- Return-from-results & mid-game snapshots ----

  describe('return-from-results and mid-game snapshots', () => {
    const RESULTS_PLAYERS = [
      { id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true, returnedToLobby: true },
      { id: 'player-2', alias: 'Bob', avatarId: 'icon-2', connected: true, returnedToLobby: false },
      { id: 'player-3', alias: 'Carol', avatarId: 'icon-3', connected: false, returnedToLobby: false },
    ];

    const resultsSnapshot = {
      roomCode: ROOM_CODE,
      phase: GamePhase.RESULTS,
      currentRound: 3,
      totalRounds: 3,
      timerSeconds: 0,
      serverTimestamp: 0,
      imageUrl: null,
      hasSubmitted: false,
      submittedCount: 0,
      players: RESULTS_PLAYERS,
      hostId: PLAYER_ID,
    };

    // The outer beforeEach already created a lobby with a LOBBY snapshot; destroy it
    // (closing its BroadcastChannel, which would otherwise PONG the new tab as a
    // duplicate) and re-create with the stub set up by the calling test.
    async function recreate(): Promise<void> {
      fixture.destroy();
      fixture = TestBed.createComponent(LobbyComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
      await new Promise<void>(resolve => setTimeout(resolve, 60));
    }

    it('RESULTS-phase snapshot shows the full game roster and the waiting note', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(of(resultsSnapshot));
      await recreate();

      expect(component.players().length).toBe(3);
      expect(component.waitingForResults()).toBeTrue();
      const compiled = fixture.nativeElement as HTMLElement;
      expect(compiled.textContent).toContain('Waiting for players');
    });

    it('PLAYER_RETURNED event marks returned cards is-returned and the rest is-away', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(of(resultsSnapshot));
      await recreate();

      wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
        type: 'PLAYER_RETURNED',
        players: RESULTS_PLAYERS,
        hostId: PLAYER_ID,
      });
      fixture.detectChanges();

      const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('app-player-card');
      expect(cards[0].classList.contains('is-returned')).toBeTrue();
      expect(cards[1].classList.contains('is-away')).toBeTrue();
      expect(cards[2].classList.contains('is-away')).toBeTrue();
    });

    it('GAME_RESET clears the waiting-for-results state', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(of(resultsSnapshot));
      await recreate();
      expect(component.waitingForResults()).toBeTrue();

      wsSubscriptionCallbacks[`/topic/room/${ROOM_CODE}`]({
        type: 'GAME_RESET',
        players: RESULTS_PLAYERS.map(p => ({ ...p, returnedToLobby: false })),
        hostId: PLAYER_ID,
      });
      fixture.detectChanges();

      expect(component.waitingForResults()).toBeFalse();
    });

    it('in a fresh lobby every player counts as returned (all green)', () => {
      expect(component.waitingForResults()).toBeFalse();
      for (const p of component.players()) {
        expect(component.isPlayerReturned(p)).toBeTrue();
      }
    });

    it('mid-game snapshot auto-joins the running game instead of kicking home', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(of({
        ...resultsSnapshot,
        phase: GamePhase.PROMPTING,
        currentRound: 1,
      }));
      await recreate();

      expect(routerSpy.navigate).toHaveBeenCalledWith(['/game', ROOM_CODE]);
      expect(playerServiceSpy.clearLocalStorage).not.toHaveBeenCalled();
    });

    it('snapshot 4xx error clears storage and navigates home', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 409, statusText: 'Conflict' }))
      );
      await recreate();

      expect(playerServiceSpy.clearLocalStorage).toHaveBeenCalledWith(ROOM_CODE);
      expect(routerSpy.navigate).toHaveBeenCalledWith(['/']);
    });

    it('transient network error does not destroy the session', async () => {
      roomApiSpy.getGameStateSnapshot.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' }))
      );
      await recreate();

      expect(playerServiceSpy.clearLocalStorage).not.toHaveBeenCalled();
      expect(routerSpy.navigate).not.toHaveBeenCalledWith(['/']);
    });
  });

  describe('roster reconciliation poll', () => {
    afterEach(() => {
      try { jasmine.clock().uninstall(); } catch { /* not installed */ }
    });

    it('re-fetches the snapshot on a timer to self-heal a missed roster event', () => {
      // Tear down the real-timer component from the outer beforeEach, then drive a
      // fresh one under a fake clock so we can advance past the reconcile interval.
      fixture.destroy();
      jasmine.clock().install();
      fixture = TestBed.createComponent(LobbyComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
      jasmine.clock().tick(60); // pass the duplicate-tab check → initializeLobby + poll start

      const callsAfterInit = roomApiSpy.getGameStateSnapshot.calls.count();
      jasmine.clock().tick(8000); // one reconcile interval

      expect(roomApiSpy.getGameStateSnapshot.calls.count()).toBeGreaterThan(callsAfterInit);
    });

    it('stops polling once the lobby is destroyed', () => {
      fixture.destroy();
      jasmine.clock().install();
      fixture = TestBed.createComponent(LobbyComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
      jasmine.clock().tick(60);

      fixture.destroy();
      const callsAfterDestroy = roomApiSpy.getGameStateSnapshot.calls.count();
      jasmine.clock().tick(8000);

      expect(roomApiSpy.getGameStateSnapshot.calls.count()).toBe(callsAfterDestroy);
    });
  });
});
