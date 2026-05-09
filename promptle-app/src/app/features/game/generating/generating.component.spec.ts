import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { GeneratingComponent } from './generating.component';
import { GENERATING_MESSAGES } from './messages';
import { GRID_COLS, GRID_ROWS } from './pixel-grid';

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
    expect(compiled.querySelectorAll('.pixel-cell--button').length).toBe(18);
    expect(compiled.querySelectorAll('.pixel-cell--antenna').length).toBe(10);
  });

  it('shows a rotating message from the message pool', fakeAsync(() => {
    tick(600);
    fixture.detectChanges();
    expect(component.currentMessage()).toBeTruthy();
    expect(GENERATING_MESSAGES).toContain(component.currentMessage());
  }));

  it('toggles message visibility', fakeAsync(() => {
    tick(600);
    fixture.detectChanges();
    expect(component.messageVisible()).toBeTrue();

    tick(5000);
    fixture.detectChanges();
    expect(component.messageVisible()).toBeFalse();
  }));

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

  it('handles button press with visual feedback', fakeAsync(() => {
    const compiled = fixture.nativeElement as HTMLElement;
    const buttonCell = compiled.querySelector('.pixel-cell--button') as HTMLElement;
    buttonCell.click();
    fixture.detectChanges();
    expect(component.pressedButton()).toBeGreaterThanOrEqual(0);

    tick(300);
    fixture.detectChanges();
    expect(component.pressedButton()).toBe(-1);
  }));

  it('does not render any input or textarea', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input')).toBeNull();
    expect(compiled.querySelector('textarea')).toBeNull();
  });
});
