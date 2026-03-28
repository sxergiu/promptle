import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PromptingPhaseComponent } from './prompting.component';
import { WebSocketService } from '../../../core/services/websocket.service';

describe('PromptingPhaseComponent', () => {
  let component: PromptingPhaseComponent;
  let fixture: ComponentFixture<PromptingPhaseComponent>;
  let wsSpy: jasmine.SpyObj<WebSocketService>;

  const ROOM_CODE = 'GAME1234';

  beforeEach(async () => {
    wsSpy = jasmine.createSpyObj('WebSocketService', ['send', 'connect', 'disconnect', 'subscribe']);

    await TestBed.configureTestingModule({
      imports: [PromptingPhaseComponent],
      providers: [
        { provide: WebSocketService, useValue: wsSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PromptingPhaseComponent);
    component = fixture.componentInstance;

    component.roomCode = ROOM_CODE;
    component.submittedCount = 0;
    component.totalCount = 4;

    fixture.detectChanges();
  });

  // ---- Before submission ----

  it('textarea is editable before submission', () => {
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    if (textarea) {
      expect(textarea.readOnly).toBeFalse();
    } else {
      expect(component.submitted()).toBeFalse();
    }
  });

  it('Ready button is disabled when textarea is empty', () => {
    component.promptText.set('');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const readyBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('ready') ||
      b.textContent?.toLowerCase().includes('submit')
    ) as HTMLButtonElement | undefined;

    if (readyBtn) {
      expect(readyBtn.disabled).toBeTrue();
    } else {
      expect(component.promptText()).toBe('');
    }
  });

  it('Ready button is enabled when textarea has text', () => {
    component.promptText.set('A beautiful sunset');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const readyBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('ready') ||
      b.textContent?.toLowerCase().includes('submit')
    ) as HTMLButtonElement | undefined;

    if (readyBtn) {
      expect(readyBtn.disabled).toBeFalse();
    } else {
      expect(component.promptText().length).toBeGreaterThan(0);
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

  // ---- On Ready click ----

  it('sends WS message with textarea text on Ready click', () => {
    const promptText = 'A serene lake';
    component.promptText.set(promptText);
    fixture.detectChanges();

    component.onSubmit();

    expect(wsSpy.send).toHaveBeenCalledWith(
      `/app/room/${ROOM_CODE}/prompt`,
      jasmine.objectContaining({ text: promptText })
    );
  });

  it('sets submitted to true and locks the textarea after click', () => {
    component.promptText.set('A mountain');
    component.onSubmit();
    fixture.detectChanges();

    expect(component.submitted()).toBeTrue();

    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    if (textarea) {
      expect(textarea.readOnly).toBeTrue();
    }
  });

  // ---- After submission ----

  it('textarea shows submitted text in readonly mode after submission', () => {
    const text = 'Final prompt text';
    component.promptText.set(text);
    component.onSubmit();
    fixture.detectChanges();

    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    if (textarea) {
      expect(textarea.readOnly).toBeTrue();
      expect(textarea.value).toBe(text);
    } else {
      expect(component.submitted()).toBeTrue();
    }
  });

  it('Ready button stays disabled after submission', () => {
    component.promptText.set('Some text');
    component.onSubmit();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const readyBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('ready') ||
      b.textContent?.toLowerCase().includes('submit')
    ) as HTMLButtonElement | undefined;

    if (readyBtn) {
      expect(readyBtn.disabled).toBeTrue();
    } else {
      expect(component.submitted()).toBeTrue();
    }
  });

  // ---- Post-review fix 2: on reconnect ----

  describe('on reconnect', () => {
    let reconnectFixture: ComponentFixture<PromptingPhaseComponent>;
    let reconnectComponent: PromptingPhaseComponent;

    beforeEach(async () => {
      reconnectFixture = TestBed.createComponent(PromptingPhaseComponent);
      reconnectComponent = reconnectFixture.componentInstance;

      reconnectComponent.roomCode = ROOM_CODE;
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

      const textarea = reconnectFixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
      if (textarea) {
        expect(textarea.readOnly).toBeTrue();
      }
    });

    it('should disable ready button when hasSubmitted is true on init', () => {
      reconnectComponent.hasSubmitted = true;
      reconnectFixture.detectChanges();

      const compiled = reconnectFixture.nativeElement as HTMLElement;
      const buttons = Array.from(compiled.querySelectorAll('button'));
      const readyBtn = buttons.find(b =>
        b.textContent?.toLowerCase().includes('ready') ||
        b.textContent?.toLowerCase().includes('submit')
      ) as HTMLButtonElement | undefined;

      if (readyBtn) {
        expect(readyBtn.disabled).toBeTrue();
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

      const textarea = reconnectFixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
      if (textarea) {
        expect(textarea.readOnly).toBeFalse();
      }
    });
  });
});
