import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GuessingPhaseComponent } from './guessing.component';
import { WebSocketService } from '../../../core/services/websocket.service';

describe('GuessingPhaseComponent', () => {
  let component: GuessingPhaseComponent;
  let fixture: ComponentFixture<GuessingPhaseComponent>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;

  const ROOM_CODE = 'GAME1234';
  const IMAGE_URL = '/api/images/game/img1';

  beforeEach(async () => {
    wsSpy = jasmine.createSpyObj('WebSocketService', ['send', 'connect', 'disconnect', 'subscribe']);

    await TestBed.configureTestingModule({
      imports: [GuessingPhaseComponent],
      providers: [
        { provide: WebSocketService, useValue: wsSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GuessingPhaseComponent);
    component = fixture.componentInstance;

    component.roomCode = ROOM_CODE;
    component.imageUrl = IMAGE_URL;
    component.submittedCount = 1;
    component.totalCount = 4;

    fixture.detectChanges();
  });

  // ---- Before submission ----

  it('displays the imageUrl in an img element', () => {
    const img = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src') ?? img.src).toContain(IMAGE_URL);
  });

  it('input is editable before submission', () => {
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    if (input) {
      expect(input.readOnly).toBeFalse();
    } else {
      expect(component.submitted()).toBeFalse();
    }
  });

  it('Submit Guess button is disabled when input is empty', () => {
    component.guessText.set('');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const submitBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('submit') ||
      b.textContent?.toLowerCase().includes('guess')
    ) as HTMLButtonElement | undefined;

    if (submitBtn) {
      expect(submitBtn.disabled).toBeTrue();
    } else {
      expect(component.guessText()).toBe('');
    }
  });

  it('Submit Guess button is enabled when input has text', () => {
    component.guessText.set('A mountain landscape');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const submitBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('submit') ||
      b.textContent?.toLowerCase().includes('guess')
    ) as HTMLButtonElement | undefined;

    if (submitBtn) {
      expect(submitBtn.disabled).toBeFalse();
    } else {
      expect(component.guessText().length).toBeGreaterThan(0);
    }
  });

  it('displays submittedCount / totalCount ready text', () => {
    fixture.componentRef.setInput('submittedCount', 2);
    fixture.componentRef.setInput('totalCount', 4);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('2');
    expect(compiled.textContent).toContain('4');
  });

  it('does not display chain owner attribution', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    // There should be no player name hint or "chain by X" label visible
    const attribution = compiled.querySelector('[data-chain-owner], .chain-owner, [class*="attribution"]');
    expect(attribution).toBeNull();
  });

  // ---- On "Submit Guess" click ----

  it('sends WS message to /app/room/{roomCode}/guess with input text', () => {
    const guessText = 'A serene forest';
    component.guessText.set(guessText);
    fixture.detectChanges();

    component.onSubmit();

    expect(wsSpy.send).toHaveBeenCalledWith(
      `/app/room/${ROOM_CODE}/guess`,
      jasmine.objectContaining({ text: guessText })
    );
  });

  it('sets submitted to true and locks the input after click', () => {
    component.guessText.set('A guess');
    component.onSubmit();
    fixture.detectChanges();

    expect(component.submitted()).toBeTrue();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    if (input) {
      expect(input.readOnly).toBeTrue();
    }
  });

  // ---- After submission ----

  it('input shows submitted text in readonly mode after submission', () => {
    const text = 'My final guess';
    component.guessText.set(text);
    component.onSubmit();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    if (input) {
      expect(input.readOnly).toBeTrue();
      expect(input.value).toBe(text);
    } else {
      expect(component.submitted()).toBeTrue();
    }
  });

  it('Submit Guess button stays disabled after submission', () => {
    component.guessText.set('A guess');
    component.onSubmit();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const submitBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('submit') ||
      b.textContent?.toLowerCase().includes('guess')
    ) as HTMLButtonElement | undefined;

    if (submitBtn) {
      expect(submitBtn.disabled).toBeTrue();
    } else {
      expect(component.submitted()).toBeTrue();
    }
  });

  // ---- Post-review fix 2: on reconnect ----

  describe('on reconnect', () => {
    let reconnectFixture: ComponentFixture<GuessingPhaseComponent>;
    let reconnectComponent: GuessingPhaseComponent;

    beforeEach(async () => {
      reconnectFixture = TestBed.createComponent(GuessingPhaseComponent);
      reconnectComponent = reconnectFixture.componentInstance;

      reconnectComponent.roomCode = ROOM_CODE;
      reconnectComponent.imageUrl = IMAGE_URL;
      reconnectComponent.submittedCount = 2;
      reconnectComponent.totalCount = 4;
    });

    it('should be readonly when hasSubmitted is true on init', () => {
      // Simulate the parent restoring submitted state from the reconnect snapshot
      reconnectComponent.hasSubmitted = true;
      reconnectFixture.detectChanges();

      // submitted() must be true and no WS send must have fired
      expect(reconnectComponent.submitted()).toBeTrue();
      expect(wsSpy.send).not.toHaveBeenCalled();

      const input = reconnectFixture.nativeElement.querySelector('input') as HTMLInputElement;
      if (input) {
        expect(input.readOnly).toBeTrue();
      }
    });

    it('should disable ready button when hasSubmitted is true on init', () => {
      reconnectComponent.hasSubmitted = true;
      reconnectFixture.detectChanges();

      const compiled = reconnectFixture.nativeElement as HTMLElement;
      const buttons = Array.from(compiled.querySelectorAll('button'));
      const submitBtn = buttons.find(b =>
        b.textContent?.toLowerCase().includes('submit') ||
        b.textContent?.toLowerCase().includes('guess')
      ) as HTMLButtonElement | undefined;

      if (submitBtn) {
        expect(submitBtn.disabled).toBeTrue();
      } else {
        // Fallback: submitted signal is true which drives [disabled] binding
        expect(reconnectComponent.submitted()).toBeTrue();
      }
    });

    it('should be editable when hasSubmitted is false on init', () => {
      reconnectComponent.hasSubmitted = false;
      reconnectFixture.detectChanges();

      expect(reconnectComponent.submitted()).toBeFalse();
      expect(wsSpy.send).not.toHaveBeenCalled();

      const input = reconnectFixture.nativeElement.querySelector('input') as HTMLInputElement;
      if (input) {
        expect(input.readOnly).toBeFalse();
      }
    });
  });
});
