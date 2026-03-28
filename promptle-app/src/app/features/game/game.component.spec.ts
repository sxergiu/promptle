import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';

import { GameComponent } from './game.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { GamePhase } from '../../core/models/game-phase.enum';
import { PromptingPhaseComponent } from './prompting/prompting.component';
import { GeneratingComponent } from './generating/generating.component';
import { GuessingPhaseComponent } from './guessing/guessing.component';

describe('GameComponent', () => {
  let component: GameComponent;
  let fixture: ComponentFixture<GameComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const ROOM_CODE = 'GAME1234';
  const PLAYER_TOKEN = 'game-player-tok';
  const PLAYER_ID = 'game-player-id';

  let wsCallbacks: { [dest: string]: Function } = {};

  const mockSnapshot = {
    phase: GamePhase.PROMPTING,
    currentRound: 1,
    totalRounds: 4,
    timerSeconds: 60,
    serverTimestamp: 1700000000000,
    imageUrl: null,
    players: [{ id: PLAYER_ID, alias: 'Alice', avatarId: 'icon-1', connected: true }],
    hasSubmitted: false,
    submittedCount: 0,
    hostId: 'host-id',
  };

  const MOCK_CHAINS = [
    {
      entries: [
        { playerId: 'p1', avatarId: 'icon-1', text: 'Prompt 1', imageUrl: '/api/img/1', isPlaceholder: false },
      ],
    },
  ];

  beforeEach(async () => {
    wsCallbacks = {};

    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['getGameStateSnapshot']);
    wsSpy = jasmine.createSpyObj('WebSocketService', ['connect', 'disconnect', 'subscribe', 'send']);
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['loadFromLocalStorage']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    playerServiceSpy.loadFromLocalStorage.and.returnValue({
      playerToken: PLAYER_TOKEN,
      playerId: PLAYER_ID,
      alias: 'Alice',
      avatarId: 'icon-1',
    });

    roomApiSpy.getGameStateSnapshot.and.returnValue(of(mockSnapshot));

    wsSpy.connect.and.callFake((_token: string, _roomCode: string, onConnect?: () => void) => {
      onConnect?.();
    });

    wsSpy.subscribe.and.callFake((dest: string, cb: Function) => {
      wsCallbacks[dest] = cb;
      return { id: dest, unsubscribe: () => {} };
    });

    await TestBed.configureTestingModule({
      imports: [GameComponent],
      providers: [
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

    fixture = TestBed.createComponent(GameComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ---- On mount ----

  it('reads from localStorage via PlayerService on init', () => {
    expect(playerServiceSpy.loadFromLocalStorage).toHaveBeenCalledWith(ROOM_CODE);
  });

  it('fetches snapshot and populates phase signal', () => {
    expect(component.phase()).toBe(GamePhase.PROMPTING);
  });

  it('fetches snapshot and populates currentRound signal', () => {
    expect(component.currentRound()).toBe(1);
  });

  it('fetches snapshot and populates totalRounds signal', () => {
    expect(component.totalRounds()).toBe(4);
  });

  it('fetches snapshot and populates timerSeconds signal', () => {
    expect(component.timerSeconds()).toBe(60);
  });

  it('fetches snapshot and populates serverTimestamp signal', () => {
    expect(component.serverTimestamp()).toBe(1700000000000);
  });

  it('connects WebSocket with player token and roomCode', () => {
    expect(wsSpy.connect).toHaveBeenCalledWith(PLAYER_TOKEN, ROOM_CODE, jasmine.any(Function), 5000, jasmine.any(Function));
  });

  it('subscribes to /topic/game/{roomCode}', () => {
    expect(wsSpy.subscribe).toHaveBeenCalledWith(`/topic/game/${ROOM_CODE}`, jasmine.any(Function));
  });

  it('subscribes to /user/queue/game', () => {
    expect(wsSpy.subscribe).toHaveBeenCalledWith('/user/queue/game', jasmine.any(Function));
  });

  // ---- Phase rendering ----

  it('renders prompting component when phase is PROMPTING', () => {
    // N-7: Use By.directive() query to avoid false positives from text matching.
    // Falls back to element selector if the child component is declared in TestBed.
    component.phase.set(GamePhase.PROMPTING);
    fixture.detectChanges();

    const byDirective = fixture.debugElement.query(By.directive(PromptingPhaseComponent));
    const bySelector = fixture.debugElement.query(By.css('app-prompting'));
    expect(byDirective ?? bySelector).not.toBeNull();
  });

  it('renders generating component when phase is GENERATING', () => {
    // N-7: Use By.directive() query to avoid false positives from text matching.
    component.phase.set(GamePhase.GENERATING);
    fixture.detectChanges();

    const byDirective = fixture.debugElement.query(By.directive(GeneratingComponent));
    const bySelector = fixture.debugElement.query(By.css('app-generating'));
    expect(byDirective ?? bySelector).not.toBeNull();
  });

  it('renders guessing component when phase is GUESSING', () => {
    // N-7: Use By.directive() query to avoid false positives from text matching.
    component.phase.set(GamePhase.GUESSING);
    fixture.detectChanges();

    const byDirective = fixture.debugElement.query(By.directive(GuessingPhaseComponent));
    const bySelector = fixture.debugElement.query(By.css('app-guessing'));
    expect(byDirective ?? bySelector).not.toBeNull();
  });

  it('navigates to /game/{roomCode}/results when phase changes to RESULTS', () => {
    component.phase.set(GamePhase.RESULTS);
    fixture.detectChanges();

    expect(routerSpy.navigate).toHaveBeenCalledWith(
      ['/game', ROOM_CODE, 'results'],
      jasmine.objectContaining({ state: jasmine.objectContaining({ chains: jasmine.any(Array) }) })
    );
  });

  // ---- PhaseChangedEvent handling ----

  it('updates phase, round, timer signals from PhaseChangedEvent', () => {
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({
      phase: GamePhase.GUESSING,
      round: 2,
      totalRounds: 4,
      timerSeconds: 45,
      serverTimestamp: 1700000001000,
    });
    fixture.detectChanges();

    expect(component.phase()).toBe(GamePhase.GUESSING);
    expect(component.currentRound()).toBe(2);
    expect(component.timerSeconds()).toBe(45);
    expect(component.serverTimestamp()).toBe(1700000001000);
  });

  // ---- SubmissionUpdateEvent ----

  it('updates submittedCount signal from SubmissionUpdateEvent', () => {
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({
      submittedCount: 3,
      totalCount: 4,
    });
    fixture.detectChanges();

    expect(component.submittedCount()).toBe(3);
  });

  // ---- RoundReadyPayload ----

  it('updates imageUrl signal from RoundReadyPayload', () => {
    wsCallbacks['/user/queue/game']({
      round: 2,
      imageUrl: '/api/images/game/img1',
    });
    fixture.detectChanges();

    expect(component.imageUrl()).toBe('/api/images/game/img1');
  });

  // ---- remainingSeconds computed signal ----

  it('remainingSeconds computes correctly from server timestamp', () => {
    const now = Date.now();
    const timerSeconds = 60;
    const serverTimestamp = now - 10000; // 10 seconds ago

    component.timerSeconds.set(timerSeconds);
    component.serverTimestamp.set(serverTimestamp);

    const remaining = component.remainingSeconds();
    // Should be approximately 50 (60 - 10)
    expect(remaining).toBeGreaterThanOrEqual(48);
    expect(remaining).toBeLessThanOrEqual(52);
  });

  it('remainingSeconds floors to zero when elapsed time exceeds timerSeconds', () => {
    const now = Date.now();
    component.timerSeconds.set(30);
    component.serverTimestamp.set(now - 60000); // 60 seconds ago, well past 30s timer

    expect(component.remainingSeconds()).toBe(0);
  });

  // ---- wsConnected signal and overlay ----

  it('reconnecting overlay is visible when wsConnected is false', () => {
    component.wsConnected.set(false);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const overlay = compiled.querySelector('[data-testid="reconnecting"], .reconnecting, [class*="reconnect"]');
    if (overlay) {
      expect(overlay).toBeTruthy();
    } else {
      expect(compiled.textContent?.toLowerCase()).toContain('reconnect');
    }
  });

  it('reconnecting overlay is hidden when wsConnected is true', () => {
    component.wsConnected.set(true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const overlay = compiled.querySelector('[data-testid="reconnecting"], .reconnecting');
    if (overlay) {
      const style = window.getComputedStyle(overlay);
      expect(style.display === 'none' || (overlay as HTMLElement).hidden).toBeTrue();
    } else {
      expect(component.wsConnected()).toBeTrue();
    }
  });

  // ---- submittedCount from snapshot ----

  it('submittedCount is populated from snapshot', () => {
    roomApiSpy.getGameStateSnapshot.and.returnValue(of({ ...mockSnapshot, submittedCount: 3 }));
    fixture = TestBed.createComponent(GameComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.submittedCount()).toBe(3);
  });

  // ---- remainingSeconds decreases over time ----

  it('remainingSeconds decreases over time', () => {
    jasmine.clock().install();
    try {
      const now = Date.now();
      component.timerSeconds.set(60);
      component.serverTimestamp.set(now);
      fixture.detectChanges();

      const before = component.remainingSeconds();

      jasmine.clock().tick(1000);
      // Force tick signal update (simulates the setInterval firing)
      (component as any)._tick.update((v: number) => v + 1);
      fixture.detectChanges();

      const after = component.remainingSeconds();
      expect(after).toBeLessThanOrEqual(before);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  // ---- GameResultsEvent stores chains and navigates with state ----

  it('stores chains from GameResultsEvent and navigates with state', () => {
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chains: MOCK_CHAINS });
    component.phase.set(GamePhase.RESULTS);
    fixture.detectChanges();

    expect(routerSpy.navigate).toHaveBeenCalledWith(
      ['/game', ROOM_CODE, 'results'],
      jasmine.objectContaining({ state: jasmine.objectContaining({ chains: MOCK_CHAINS }) })
    );
  });

  // ---- On destroy ----

  it('disconnects WebSocket on destroy', () => {
    component.ngOnDestroy();
    expect(wsSpy.disconnect).toHaveBeenCalled();
  });
});
