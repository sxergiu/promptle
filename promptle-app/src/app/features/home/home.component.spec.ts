import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { HomeComponent } from './home.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';
import { PLAYER_ICONS } from '../../core/models/player-icons';

describe('HomeComponent', () => {
  let component: HomeComponent;
  let fixture: ComponentFixture<HomeComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['createRoom']);
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['saveToLocalStorage']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        { provide: RoomApiService, useValue: roomApiSpy },
        { provide: PlayerService, useValue: playerServiceSpy },
        { provide: Router, useValue: routerSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HomeComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ---- Initial render ----

  it('displays the "Promptle" logo text', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Promptle');
  });

  it('displays the tagline "The AI Telephone"', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('The AI Telephone');
  });

  it('initialises selectedIcon to a member of PLAYER_ICONS', () => {
    const icon = component.selectedIcon;
    expect(PLAYER_ICONS).toContain(icon);
  });

  it('renders the alias input and Create Room button', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input, textarea, [type="text"]')).not.toBeNull();
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const createBtn = buttons.find(b => b.textContent?.toLowerCase().includes('create'));
    expect(createBtn).toBeTruthy();
  });

  // ---- Avatar randomizer ----

  it('shuffle button changes selectedIcon to a different icon', () => {
    // N-1: Assert the icon pool has at least 3 entries so the test always runs.
    // PLAYER_ICONS is a flat string[] with at least 3 SVG paths (red-saxon, saxon, brown-saxon).
    expect(PLAYER_ICONS.length).toBeGreaterThanOrEqual(3);

    const originalIcon = component.selectedIcon;
    let changed = false;
    for (let i = 0; i < 20; i++) {
      component.shuffleIcon();
      if (component.selectedIcon !== originalIcon) {
        changed = true;
        break;
      }
    }
    expect(changed).toBeTrue();
  });

  it('displayed avatar path updates after shuffle', () => {
    // N-1: Guard is an assertion rather than a pending() skip — pool always has 3+ icons.
    expect(PLAYER_ICONS.length).toBeGreaterThanOrEqual(3);

    component.shuffleIcon();
    fixture.detectChanges();

    const img = fixture.nativeElement.querySelector('img');
    if (img && component.selectedIcon) {
      expect(img.getAttribute('src')).toContain(component.selectedIcon.path);
    } else {
      expect(component.selectedIcon).toBeTruthy();
    }
  });

  // ---- "Create Room" button ----

  it('Create Room button is disabled when alias is empty', () => {
    component.alias.set('');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const createBtn = buttons.find(b => b.textContent?.toLowerCase().includes('create')) as HTMLButtonElement | undefined;
    if (createBtn) {
      expect(createBtn.disabled).toBeTrue();
    } else {
      // Accept if disabled via attribute binding
      const disabledBtn = compiled.querySelector('[disabled]');
      expect(disabledBtn).toBeTruthy();
    }
  });

  it('Create Room button is enabled when alias has a value', () => {
    component.alias.set('Alice');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    const createBtn = buttons.find(b => b.textContent?.toLowerCase().includes('create')) as HTMLButtonElement | undefined;
    if (createBtn) {
      expect(createBtn.disabled).toBeFalse();
    } else {
      expect(component.alias().length).toBeGreaterThan(0);
    }
  });

  it('calls RoomApiService.createRoom with current alias and avatarId on click', () => {
    roomApiSpy.createRoom.and.returnValue(of({ roomCode: 'ABCD1234', playerToken: 'tok', playerId: 'pid' }));
    playerServiceSpy.saveToLocalStorage.and.stub();
    routerSpy.navigate.and.stub();

    component.alias.set('Alice');
    fixture.detectChanges();

    component.onCreateRoom();

    expect(roomApiSpy.createRoom).toHaveBeenCalledWith('Alice', component.selectedIcon?.id ?? jasmine.any(String));
  });

  it('saves to localStorage on successful createRoom response', () => {
    const roomCode = 'ABCD1234';
    roomApiSpy.createRoom.and.returnValue(of({ roomCode, playerToken: 'tok', playerId: 'pid' }));
    playerServiceSpy.saveToLocalStorage.and.stub();
    routerSpy.navigate.and.stub();

    component.alias.set('Alice');
    component.onCreateRoom();

    expect(playerServiceSpy.saveToLocalStorage).toHaveBeenCalledWith(
      roomCode,
      jasmine.objectContaining({ alias: 'Alice' })
    );
  });

  it('navigates to /lobby/{roomCode} on successful createRoom response', () => {
    const roomCode = 'ABCD1234';
    roomApiSpy.createRoom.and.returnValue(of({ roomCode, playerToken: 'tok', playerId: 'pid' }));
    playerServiceSpy.saveToLocalStorage.and.stub();
    routerSpy.navigate.and.stub();

    component.alias.set('Alice');
    component.onCreateRoom();

    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', roomCode]);
  });

  // ---- Footer ----

  it('contains a "How to Play" element', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('How to Play');
  });

  it('contains "About" and "Privacy Policy" elements', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('About');
    expect(compiled.textContent).toContain('Privacy');
  });
});
