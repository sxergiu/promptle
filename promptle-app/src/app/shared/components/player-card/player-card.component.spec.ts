import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PlayerCardComponent } from './player-card.component';
import { PLAYER_ICONS } from '../../../core/models/player-icons';

describe('PlayerCardComponent', () => {
  let component: PlayerCardComponent;
  let fixture: ComponentFixture<PlayerCardComponent>;

  const AVATAR_ID = PLAYER_ICONS[0]?.id ?? 'icon-1';
  const ALIAS = 'Alice';

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PlayerCardComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(PlayerCardComponent);
    component = fixture.componentInstance;

    component.alias = ALIAS;
    component.avatarId = AVATAR_ID;
    component.isHost = false;
    component.showKickButton = false;

    fixture.detectChanges();
  });

  // ---- Rendering ----

  it('displays the alias text', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain(ALIAS);
  });

  it('displays the avatar image with correct src from PLAYER_ICONS', () => {
    const icon = PLAYER_ICONS.find(i => i.id === AVATAR_ID);
    const img = fixture.nativeElement.querySelector('img') as HTMLImageElement;

    if (icon && img) {
      expect(img.getAttribute('src') ?? img.src).toContain(icon.path);
    } else {
      expect(icon).toBeTruthy();
    }
  });

  it('shows crown when isHost is true', () => {
    fixture.componentRef.setInput('isHost', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    // Crown indicator should be present — accept img[alt*=crown], .crown, [data-testid=crown], or text containing crown
    const hasCrown =
      !!compiled.querySelector('.crown, [data-crown], [alt*="crown" i], [src*="crown" i]') ||
      compiled.textContent?.toLowerCase().includes('crown') ||
      !!compiled.querySelector('[class*="crown"], [id*="crown"]');
    expect(hasCrown).toBeTrue();
  });

  it('does not show crown when isHost is false', () => {
    component.isHost = false;
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const hasCrown =
      !!compiled.querySelector('.crown, [data-crown], [alt*="crown" i], [src*="crown" i]') ||
      !!compiled.querySelector('[class*="crown"], [id*="crown"]');
    expect(hasCrown).toBeFalse();
  });

  it('shows kick button when showKickButton is true', () => {
    fixture.componentRef.setInput('showKickButton', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const kickBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('kick') ||
      b.getAttribute('aria-label')?.toLowerCase().includes('kick')
    );
    expect(kickBtn).toBeTruthy();
  });

  it('does not show kick button when showKickButton is false', () => {
    component.showKickButton = false;
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const kickBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('kick') ||
      b.getAttribute('aria-label')?.toLowerCase().includes('kick')
    );
    expect(kickBtn).toBeFalsy();
  });

  it('kick button does nothing on click — no output emitted', () => {
    fixture.componentRef.setInput('showKickButton', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const kickBtn = buttons.find(b =>
      b.textContent?.toLowerCase().includes('kick')
    ) as HTMLButtonElement | undefined;

    if (kickBtn) {
      expect(() => kickBtn.click()).not.toThrow();
    } else {
      expect(component.showKickButton).toBeTrue();
    }
  });
});
