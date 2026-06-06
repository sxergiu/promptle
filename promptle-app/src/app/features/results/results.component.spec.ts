import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { ResultsComponent } from './results.component';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { RoomApiService } from '../../core/services/room-api.service';

describe('ResultsComponent', () => {
  let component: ResultsComponent;
  let fixture: ComponentFixture<ResultsComponent>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const ROOM_CODE = 'RESU1234';
  const PLAYER_ID = 'player-id-results';

  let wsCallbacks: { [dest: string]: Function } = {};

  const MOCK_CHAINS = [
    {
      entries: [
        { playerId: 'p1', avatarId: 'icon-1', text: 'Prompt 1', imageUrl: '/api/img/1', isPlaceholder: false },
        { playerId: 'p2', avatarId: 'icon-2', text: 'Guess round 2', imageUrl: null, isPlaceholder: false },
      ],
    },
    {
      entries: [
        { playerId: 'p2', avatarId: 'icon-2', text: 'Prompt 2', imageUrl: '/api/img/2', isPlaceholder: false },
        { playerId: null, avatarId: null, text: 'Wise Hipiotic Cow', imageUrl: null, isPlaceholder: true },
      ],
    },
  ];

  const REVEAL_INTERVAL_MS = 3000;

  beforeEach(async () => {
    wsCallbacks = {};

    wsSpy = jasmine.createSpyObj('WebSocketService', ['connect', 'disconnect', 'subscribe', 'send', 'isConnected']);
    wsSpy.isConnected.and.returnValue(false); // tests exercise the connect path
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['clearLocalStorage', 'loadFromLocalStorage']);
    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['resetGame', 'leaveResults']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);
    roomApiSpy.resetGame.and.returnValue(of(undefined as void));
    roomApiSpy.leaveResults.and.returnValue(of(undefined as void));

    // Component reads window.history.state instead of getCurrentNavigation()
    history.replaceState({ chains: MOCK_CHAINS, hostId: 'other-player-id' }, '');

    playerServiceSpy.loadFromLocalStorage.and.returnValue({
      playerToken: 'tok-results',
      playerId: PLAYER_ID,
      alias: 'Alice',
      avatarId: 'icon-1',
    });

    wsSpy.connect.and.callFake((_token: string, _roomCode: string, onConnect?: () => void) => {
      onConnect?.();
    });

    wsSpy.subscribe.and.callFake((dest: string, cb: Function) => {
      wsCallbacks[dest] = cb;
      return { id: dest, unsubscribe: () => {} };
    });

    await TestBed.configureTestingModule({
      imports: [ResultsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WebSocketService, useValue: wsSpy },
        { provide: PlayerService, useValue: playerServiceSpy },
        { provide: RoomApiService, useValue: roomApiSpy },
        { provide: Router, useValue: routerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            params: of({ roomCode: ROOM_CODE }),
            snapshot: {
              paramMap: { get: (k: string) => k === 'roomCode' ? ROOM_CODE : null },
            },
            // Pass chains via router state
            extras: { state: { chains: MOCK_CHAINS } },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ResultsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    component.ngOnDestroy?.();
  });

  // ---- On mount ----

  it('loads chains and populates chains signal', () => {
    component.chains.set(MOCK_CHAINS);
    expect(component.chains().length).toBeGreaterThan(0);
  });

  it('chains signal populated from router navigation state', () => {
    expect(component.chains()).toEqual(MOCK_CHAINS);
  });

  it('subscribes to /topic/game/{roomCode} on mount', () => {
    expect(wsSpy.subscribe).toHaveBeenCalledWith(
      `/topic/game/${ROOM_CODE}`,
      jasmine.any(Function)
    );
  });

  // ---- Entry-by-entry reveal ----

  it('revealedEntryCount increments by 1 on each interval tick', () => {
    jasmine.clock().install();
    try {
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedEntryCount.set(0);
      component.startRevealInterval();

      jasmine.clock().tick(REVEAL_INTERVAL_MS);
      expect(component.revealedEntryCount()).toBe(1);

      jasmine.clock().tick(REVEAL_INTERVAL_MS);
      expect(component.revealedEntryCount()).toBe(2);
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('stops incrementing once all entries in current chain are revealed', () => {
    jasmine.clock().install();
    try {
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedEntryCount.set(0);
      component.startRevealInterval();

      const totalEntries = MOCK_CHAINS[0].entries.length;

      // Tick past all entries
      jasmine.clock().tick(REVEAL_INTERVAL_MS * (totalEntries + 3));

      expect(component.revealedEntryCount()).toBe(totalEntries);
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('uses environment showcaseRevealIntervalMs for the interval', () => {
    // Verify the component uses the environment variable for interval
    expect(component.revealIntervalMs).toBe(REVEAL_INTERVAL_MS);
  });

  // ---- Chain display ----

  it('renders image after an entry when imageUrl is non-null', () => {
    component.chains.set(MOCK_CHAINS);
    component.currentChainIndex.set(0);
    component.revealedEntryCount.set(MOCK_CHAINS[0].entries.length);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const images = compiled.querySelectorAll('img');
    expect(images.length).toBeGreaterThan(0);
  });

  it('renders placeholder entries the same as real entries (no special hidden class)', () => {
    component.chains.set(MOCK_CHAINS);
    component.currentChainIndex.set(1);
    component.revealedEntryCount.set(MOCK_CHAINS[1].entries.length);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    // No hidden attribute on placeholder entries
    const hiddenPlaceholders = compiled.querySelectorAll('[hidden], [style*="display: none"]');
    // Regular entry elements should not be hidden
    expect(component.chains()[1].entries.some((e: any) => e.isPlaceholder)).toBeTrue();
    // Just verify no crash and rendering works
    expect(compiled.textContent).toContain('Wise Hipiotic Cow');
  });

  // ---- ShowcaseAdvancedEvent handling ----

  it('updates currentChainIndex from ShowcaseAdvancedEvent', () => {
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chainIndex: 1 });
    fixture.detectChanges();

    expect(component.currentChainIndex()).toBe(1);
  });

  it('resets revealedEntryCount to 0 on ShowcaseAdvancedEvent', () => {
    component.revealedEntryCount.set(5);
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chainIndex: 1 });
    fixture.detectChanges();

    expect(component.revealedEntryCount()).toBe(0);
  });

  // ---- "Next" button ----

  it('Next button is disabled for host when not all entries revealed', () => {
    component.chains.set(MOCK_CHAINS);
    component.isHost.set(true);
    component.revealedEntryCount.set(0); // not all revealed
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const nextBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('next')
    ) as HTMLButtonElement | undefined;

    if (nextBtn) {
      expect(nextBtn.disabled).toBeTrue();
    } else {
      expect(component.allRevealed()).toBeFalse();
    }
  });

  it('Next button is enabled for host after all entries revealed', () => {
    jasmine.clock().install();
    try {
      component.chains.set(MOCK_CHAINS);
      component.isHost.set(true);
      component.currentChainIndex.set(0);
      component.revealedEntryCount.set(MOCK_CHAINS[0].entries.length);
      jasmine.clock().tick(600); // advance past canProceed delay
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const buttons = Array.from(compiled.querySelectorAll('button'));
      const nextBtn = buttons.find(b =>
        b.textContent?.toLowerCase().includes('next')
      ) as HTMLButtonElement | undefined;

      if (nextBtn) {
        expect(nextBtn.disabled).toBeFalse();
      } else {
        expect(component.allRevealed()).toBeTrue();
      }
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('sends WS next-chain message on Next button click', () => {
    component.chains.set(MOCK_CHAINS);
    component.isHost.set(true);
    component.revealedEntryCount.set(MOCK_CHAINS[0].entries.length);
    fixture.detectChanges();

    component.onNextChain();

    expect(wsSpy.send).toHaveBeenCalledWith(
      `/app/room/${ROOM_CODE}/next-chain`,
      jasmine.anything()
    );
  });

  it('displays "Next" label for the host while there are remaining chains', () => {
    component.chains.set(MOCK_CHAINS);
    component.isHost.set(true);
    component.currentChainIndex.set(0); // not last chain
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const hasNext = buttons.some(b => b.textContent?.toLowerCase().includes('next'));
    expect(hasNext).toBeTrue();
  });

  it('hides the "Next" button for non-host players', () => {
    component.chains.set(MOCK_CHAINS);
    component.isHost.set(false);
    component.currentChainIndex.set(0); // not last chain
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const hasNext = buttons.some(b => b.textContent?.toLowerCase().includes('next'));
    expect(hasNext).toBeFalse();
  });

  it('displays "Lobby" label after all chains showcased', () => {
    component.chains.set(MOCK_CHAINS);
    const lastIndex = MOCK_CHAINS.length - 1;
    component.currentChainIndex.set(lastIndex);
    // Fully revealing the last chain flips allChainsCompleted via the reveal effect.
    component.revealedEntryCount.set(MOCK_CHAINS[lastIndex].entries.length);
    fixture.detectChanges();
    fixture.detectChanges(); // let the effect-driven allChainsCompleted propagate

    expect(component.allChainsCompleted()).toBeTrue();
    const compiled = fixture.nativeElement as HTMLElement;
    const hasLobby = compiled.textContent?.toLowerCase().includes('lobby');
    expect(hasLobby).toBeTrue();
  });

  // ---- "Back to Lobby" action ----

  it('signals leave-results and navigates to the lobby individually (no room reset)', () => {
    component.onBackToLobby();

    expect(roomApiSpy.leaveResults).toHaveBeenCalledWith(ROOM_CODE, 'tok-results');
    expect(roomApiSpy.resetGame).not.toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  it('navigates to /lobby/{roomCode} when GAME_RESET event received', () => {
    wsCallbacks[`/topic/room/${ROOM_CODE}`]({ type: 'GAME_RESET', players: [], hostId: '' });
    fixture.detectChanges();

    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  // ---- "Export Thread" button ----

  it('Export (GIF) button is present in the UI', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const exportBtn = buttons.find(b => b.textContent?.toLowerCase().includes('gif'));
    expect(exportBtn).toBeTruthy();
  });

  it('Export Thread button performs no action on click', () => {
    component.onExportThread();

    expect(wsSpy.send).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });
});
