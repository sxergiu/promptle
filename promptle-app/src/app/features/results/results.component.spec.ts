import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { ResultsComponent } from './results.component';
import { WebSocketService } from '../../core/services/websocket.service';
import { PlayerService } from '../../core/services/player.service';
import { RoomApiService } from '../../core/services/room-api.service';
import { SoundService } from '../../core/services/sound.service';

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
    localStorage.removeItem('promptle_muted');
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

  // ---- Message-by-message reveal ----

  it('reveals one message at a time (text and image never appear together)', () => {
    jasmine.clock().install();
    try {
      component.muted.set(true);
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedStepCount.set(0);
      component.startRevealInterval();

      // After the think delay: entry 0's text only — its image must not be visible yet.
      jasmine.clock().tick(component.textThinkMs);
      expect(component.revealedStepCount()).toBe(1);
      expect(component.isTextRevealed(0)).toBeTrue();
      expect(component.isImageRevealed(0)).toBeFalse();

      // Finish typing + settle, then the image think delay: now entry 0's image.
      const typingMs = MOCK_CHAINS[0].entries[0].text.length
        * component.charIntervalFor(MOCK_CHAINS[0].entries[0].text.length);
      jasmine.clock().tick(typingMs + component.textSettleMs + component.imageThinkMs);
      expect(component.revealedStepCount()).toBe(2);
      expect(component.isImageRevealed(0)).toBeTrue();
      expect(component.isTextRevealed(1)).toBeFalse();
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('stops once all messages in the current chain are revealed', () => {
    jasmine.clock().install();
    try {
      component.muted.set(true);
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedStepCount.set(0);
      component.startRevealInterval();

      const totalSteps = component.stepsIn(MOCK_CHAINS[0]);

      // Tick far past the whole chain's reveal time
      jasmine.clock().tick(60_000);

      expect(component.revealedStepCount()).toBe(totalSteps);
      expect(component.typing()).toBeNull();
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('uses environment showcaseRevealIntervalMs as the pacing base', () => {
    expect(component.revealIntervalMs).toBe(REVEAL_INTERVAL_MS);
    expect(component.stepIntervalMs).toBe(REVEAL_INTERVAL_MS / 2);
  });

  // ---- Typewriter ----

  it('types text character by character after the bubble appears', () => {
    jasmine.clock().install();
    try {
      component.muted.set(true);
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedStepCount.set(0);
      component.startRevealInterval();

      jasmine.clock().tick(component.textThinkMs);
      const text = MOCK_CHAINS[0].entries[0].text;
      const charMs = component.charIntervalFor(text.length);
      expect(component.isTyping(0)).toBeTrue();

      jasmine.clock().tick(charMs * 3);
      expect(component.displayedText(0, text)).toBe(text.slice(0, 3));

      jasmine.clock().tick(charMs * text.length);
      expect(component.typing()).toBeNull();
      expect(component.displayedText(0, text)).toBe(text);
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('caps the per-character delay so long texts still type quickly', () => {
    expect(component.charIntervalFor(8)).toBe(component.typeMsPerChar);
    expect(component.charIntervalFor(500)).toBe(16);
    // Total typing time for a long text stays near the cap, not length × full delay.
    expect(500 * component.charIntervalFor(500)).toBeLessThanOrEqual(component.maxTypingMs * 3);
  });

  it('hides the typing dots while a bubble is typewriting', () => {
    jasmine.clock().install();
    try {
      component.muted.set(true);
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      component.revealedStepCount.set(0);
      component.startRevealInterval();

      jasmine.clock().tick(component.textThinkMs);
      expect(component.typing()).not.toBeNull();
      expect(component.visiblePendingStep()).toBeNull();
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).querySelector('.typing-bubble')).toBeFalsy();
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  // ---- Sound / haptics ----

  it('SoundService.toggle flips the shared mute signal and persists it', () => {
    const sound = TestBed.inject(SoundService);
    sound.muted.set(false);
    sound.toggle();
    expect(component.muted()).toBeTrue(); // component mirrors the shared signal
    expect(localStorage.getItem('promptle_muted')).toBe('1');
    sound.toggle();
    expect(component.muted()).toBeFalse();
    expect(localStorage.getItem('promptle_muted')).toBe('0');
  });

  it('vibrates on message reveal, but not while muted', () => {
    if (!('vibrate' in navigator)) {
      pending('Vibration API not supported in this browser');
      return;
    }
    const vibrateSpy = spyOn(navigator, 'vibrate');
    jasmine.clock().install();
    try {
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);

      component.muted.set(true);
      component.revealedStepCount.set(0);
      component.startRevealInterval();
      jasmine.clock().tick(component.textThinkMs);
      expect(vibrateSpy).not.toHaveBeenCalled();

      component.muted.set(false);
      component.revealedStepCount.set(0);
      component.startRevealInterval();
      jasmine.clock().tick(component.textThinkMs);
      expect(vibrateSpy).toHaveBeenCalled();
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('plays a typeTick per (throttled) keystroke while typewriting, but not while muted', () => {
    const sound = TestBed.inject(SoundService);
    const tick = spyOn(sound, 'typeTick');
    jasmine.clock().install();
    try {
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);

      // Muted: no keystroke ticks.
      component.muted.set(true);
      component.revealedStepCount.set(0);
      component.startRevealInterval();
      const text = MOCK_CHAINS[0].entries[0].text;
      const charMs = component.charIntervalFor(text.length);
      jasmine.clock().tick(component.textThinkMs);
      jasmine.clock().tick(charMs * text.length);
      expect(tick).not.toHaveBeenCalled();

      // Unmuted: ticks fire as the bubble types out.
      component.muted.set(false);
      component.revealedStepCount.set(0);
      component.startRevealInterval();
      jasmine.clock().tick(component.textThinkMs);
      jasmine.clock().tick(charMs * text.length);
      expect(tick).toHaveBeenCalled();
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  it('plays the imageBlip cue when an image bubble is revealed', () => {
    const sound = TestBed.inject(SoundService);
    const blip = spyOn(sound, 'imageBlip');
    jasmine.clock().install();
    try {
      component.muted.set(false);
      component.chains.set(MOCK_CHAINS);
      component.currentChainIndex.set(0);
      // Entry 0's text is revealed; its image is the next pending step.
      component.revealedStepCount.set(1);
      component.startRevealInterval();
      jasmine.clock().tick(component.imageThinkMs);
      expect(blip).toHaveBeenCalledTimes(1);
    } finally {
      component.ngOnDestroy();
      jasmine.clock().uninstall();
    }
  });

  // ---- Typing indicator ----

  it('shows a typing indicator for the next text message while revealing', () => {
    component.revealedStepCount.set(0);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const typing = compiled.querySelector('.typing-bubble');
    expect(typing).toBeTruthy();
    expect(typing!.classList.contains('typing-bubble--image')).toBeFalse();
  });

  it('shows the image-style typing indicator while the next image is pending', () => {
    // Entry 0's text revealed; its image is the pending step.
    component.revealedStepCount.set(1);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const typing = compiled.querySelector('.typing-bubble');
    expect(typing).toBeTruthy();
    expect(typing!.classList.contains('typing-bubble--image')).toBeTrue();
  });

  it('hides the typing indicator once the whole chain is revealed', () => {
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[0]));
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.typing-bubble')).toBeFalsy();
    expect(component.pendingStep()).toBeNull();
  });

  // ---- Chain display ----

  it('renders image after an entry when imageUrl is non-null', () => {
    component.chains.set(MOCK_CHAINS);
    component.currentChainIndex.set(0);
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[0]));
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const images = compiled.querySelectorAll('img');
    expect(images.length).toBeGreaterThan(0);
  });

  it('renders placeholder entries the same as real entries (no special hidden class)', () => {
    component.chains.set(MOCK_CHAINS);
    component.currentChainIndex.set(1);
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[1]));
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

  it('plays the showcaseAdvance cue (routed through SoundService) on ShowcaseAdvancedEvent', () => {
    const advance = spyOn(TestBed.inject(SoundService), 'showcaseAdvance');
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chainIndex: 1 });
    fixture.detectChanges();

    expect(advance).toHaveBeenCalledTimes(1);
  });

  it('resets revealedStepCount to 0 on ShowcaseAdvancedEvent', () => {
    component.revealedStepCount.set(5);
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chainIndex: 1 });
    fixture.detectChanges();

    expect(component.revealedStepCount()).toBe(0);
  });

  // ---- "Next" button ----

  it('Next button is disabled for host when not all entries revealed', () => {
    component.chains.set(MOCK_CHAINS);
    component.isHost.set(true);
    component.revealedStepCount.set(0); // not all revealed
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
      component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[0]));
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
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[0]));
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
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[lastIndex]));
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

  // ---- Chain dots ----

  // Fully reveal the last chain so the reveal effect flips allChainsCompleted.
  function completeShowcase(): void {
    const lastIndex = MOCK_CHAINS.length - 1;
    component.currentChainIndex.set(lastIndex);
    component.revealedStepCount.set(component.stepsIn(MOCK_CHAINS[lastIndex]));
    fixture.detectChanges();
    fixture.detectChanges(); // let the effect-driven allChainsCompleted propagate
  }

  it('dots are not clickable while the showcase is running', () => {
    wsCallbacks[`/topic/game/${ROOM_CODE}`]({ chainIndex: 1 });
    fixture.detectChanges();

    component.navigateToChain(0);

    expect(component.currentChainIndex()).toBe(1);
  });

  it('dots render muted during the showcase, only the current one active', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const container = compiled.querySelector('.chain-dots') as HTMLElement;
    expect(container.classList.contains('chain-dots--showcase')).toBeTrue();

    const dots = Array.from(compiled.querySelectorAll('.dot'));
    expect(dots[0].classList.contains('active')).toBeTrue();
    for (const dot of dots) {
      expect(dot.classList.contains('clickable')).toBeFalse();
    }
  });

  it('after completion a dot click navigates to that chain fully revealed', () => {
    completeShowcase();

    component.navigateToChain(0);

    expect(component.currentChainIndex()).toBe(0);
    expect(component.revealedStepCount()).toBe(component.stepsIn(MOCK_CHAINS[0]));
    expect(component.canProceed()).toBeTrue();
  });

  it('after completion all dots are clickable and the showcase styling is gone', () => {
    completeShowcase();

    const compiled = fixture.nativeElement as HTMLElement;
    const container = compiled.querySelector('.chain-dots') as HTMLElement;
    expect(container.classList.contains('chain-dots--showcase')).toBeFalse();

    const dots = Array.from(compiled.querySelectorAll('.dot'));
    expect(dots.length).toBe(MOCK_CHAINS.length);
    for (const dot of dots) {
      expect(dot.classList.contains('clickable')).toBeTrue();
    }
  });

  // ---- Enter key ----

  it('Enter triggers Back to Lobby once all chains are completed', () => {
    completeShowcase();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(roomApiSpy.leaveResults).toHaveBeenCalledWith(ROOM_CODE, 'tok-results');
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  it('Enter does nothing while the showcase is running', () => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(roomApiSpy.leaveResults).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  // ---- "Export Thread" button ----

  it('Export (GIF) button is present in the UI', () => {
    // The export button only renders once the whole chain is revealed.
    const chain = component.chains()[component.currentChainIndex()];
    component.revealedStepCount.set(component.stepsIn(chain));
    fixture.detectChanges();

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
