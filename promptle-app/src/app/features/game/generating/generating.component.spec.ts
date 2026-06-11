import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GeneratingComponent } from './generating.component';
import { GENERATING_MESSAGES } from './messages';
import { GRID_COLS, GRID_ROWS } from './pixel-grid';

// The app runs zoneless (provideZonelessChangeDetection), so fakeAsync()/tick()
// are unavailable — they need zone.js/testing. Drive timer-based behaviour with
// real time instead.
const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));

describe('GeneratingComponent', () => {
  let component: GeneratingComponent;
  let fixture: ComponentFixture<GeneratingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GeneratingComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(GeneratingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  it('renders the pixel grid with the correct number of cells', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const cells = compiled.querySelectorAll('.pixel-cell');
    expect(cells.length).toBe(GRID_COLS * GRID_ROWS);
  });

  it('displays the label "Generating"', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Generating');
  });

  it('renders phone body, screen, phone icon, buttons, and antenna cells', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelectorAll('.pixel-cell--body').length).toBeGreaterThan(0);
    expect(compiled.querySelectorAll('.pixel-cell--screen').length).toBeGreaterThan(0);
    expect(compiled.querySelectorAll('.pixel-cell--phone').length).toBeGreaterThan(0);
    expect(compiled.querySelectorAll('.pixel-cell--button').length).toBe(36);
    expect(compiled.querySelectorAll('.pixel-cell--antenna').length).toBe(10);
  });

  it('shows a rotating message from the message pool', async () => {
    // First message is set ~500ms after init.
    await delay(700);
    expect(component.currentMessage()).toBeTruthy();
    expect(GENERATING_MESSAGES).toContain(component.currentMessage());
  });

  it('toggles message visibility', async () => {
    // Visible turns true at ~500ms and back to false at ~5500ms.
    await delay(700);
    expect(component.messageVisible()).toBeTrue();

    await delay(5200);
    expect(component.messageVisible()).toBeFalse();
  }, 9000);

  it('handles screen cell tap interaction', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const screenCell = compiled.querySelector('.pixel-cell--screen') as HTMLElement;
    screenCell.click();
    fixture.detectChanges();
    expect(component.poppedCells().size).toBeGreaterThan(0);
  });

  it('triggers ring animation on phone icon tap', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const phoneCell = compiled.querySelector('.pixel-cell--phone') as HTMLElement;
    phoneCell.click();
    fixture.detectChanges();
    expect(component.ringing()).toBeTrue();
  });

  it('handles button press with visual feedback', async () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const buttonCell = compiled.querySelector('.pixel-cell--button') as HTMLElement;
    buttonCell.click();
    fixture.detectChanges();
    expect(component.pressedButton()).toBeGreaterThanOrEqual(0);

    // pressedButton resets ~200ms after the press.
    await delay(300);
    expect(component.pressedButton()).toBe(-1);
  });

  it('does not render any input or textarea', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input')).toBeNull();
    expect(compiled.querySelector('textarea')).toBeNull();
  });
});
