import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PlayerSetupComponent, PlayerSetupSubmit } from './player-setup.component';
import { PLAYER_ICONS } from '../../../core/models/player-icons';

describe('PlayerSetupComponent', () => {
  let component: PlayerSetupComponent;
  let fixture: ComponentFixture<PlayerSetupComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PlayerSetupComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(PlayerSetupComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ---- Render ----

  it('displays the PROMPTLE logo and tagline', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('PROMPTLE');
    expect(text).toContain('The Telepromptle');
  });

  it('initialises selectedIcon to a member of PLAYER_ICONS', () => {
    expect(PLAYER_ICONS).toContain(component.selectedIcon);
  });

  // ---- Avatar shuffle ----

  it('shuffleIcon changes selectedIcon to a different icon', () => {
    expect(PLAYER_ICONS.length).toBeGreaterThanOrEqual(3);
    const original = component.selectedIcon;
    let changed = false;
    for (let i = 0; i < 20; i++) {
      component.shuffleIcon();
      if (component.selectedIcon !== original) { changed = true; break; }
    }
    expect(changed).toBeTrue();
  });

  // ---- Alias input ----

  it('caps the alias at 13 characters and flags rejection', () => {
    const input = (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
    input.value = 'abcdefghijklmnop'; // 16 chars
    input.dispatchEvent(new Event('input'));
    expect(component.alias().length).toBe(13);
    expect(component.aliasRejected()).toBeTrue();
  });

  // ---- Play button ----

  it('PLAY button is disabled when alias is empty', () => {
    component.alias.set('');
    fixture.detectChanges();
    const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(b => b.textContent?.toLowerCase().includes('play')) as HTMLButtonElement;
    expect(btn.disabled).toBeTrue();
  });

  it('PLAY button is disabled while busy', () => {
    component.alias.set('Alice');
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(b => b.textContent?.toLowerCase().includes('play')) as HTMLButtonElement;
    expect(btn.disabled).toBeTrue();
  });

  it('emits (play) with the trimmed alias and selected avatar id on play', () => {
    let emitted: PlayerSetupSubmit | undefined;
    component.play.subscribe((v) => (emitted = v));

    component.alias.set('  Alice  ');
    component.onPlay();

    expect(emitted).toEqual({ alias: 'Alice', avatarId: component.selectedIcon.id });
  });

  it('does not emit (play) when alias is blank', () => {
    const spy = jasmine.createSpy('play');
    component.play.subscribe(spy);
    component.alias.set('   ');
    component.onPlay();
    expect(spy).not.toHaveBeenCalled();
  });

  it('renders an error message when provided', () => {
    fixture.componentRef.setInput('errorMessage', 'Room is full');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Room is full');
  });
});
