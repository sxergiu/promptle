import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GeneratingComponent } from './generating.component';

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

  it('displays a loading spinner', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const spinner =
      compiled.querySelector('mat-spinner') ||
      compiled.querySelector('mat-progress-spinner') ||
      compiled.querySelector('[class*="spinner"]') ||
      compiled.querySelector('[role="progressbar"]');
    expect(spinner).toBeTruthy();
  });

  it('displays the label "Generating..."', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Generating');
  });

  it('does not render any input, textarea, or button', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input')).toBeNull();
    expect(compiled.querySelector('textarea')).toBeNull();
    expect(compiled.querySelector('button')).toBeNull();
  });
});
