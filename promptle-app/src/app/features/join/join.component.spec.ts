import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { JoinComponent } from './join.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';

describe('JoinComponent', () => {
  let component: JoinComponent;
  let fixture: ComponentFixture<JoinComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const ROOM_CODE = 'TEST1234';

  beforeEach(async () => {
    roomApiSpy = jasmine.createSpyObj('RoomApiService', ['joinRoom']);
    playerServiceSpy = jasmine.createSpyObj('PlayerService', ['saveToLocalStorage', 'loadFromLocalStorage']);
    playerServiceSpy.loadFromLocalStorage.and.returnValue(null);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [JoinComponent],
      providers: [
        { provide: RoomApiService, useValue: roomApiSpy },
        { provide: PlayerService, useValue: playerServiceSpy },
        { provide: Router, useValue: routerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            params: of({ roomCode: ROOM_CODE }),
            snapshot: {
              paramMap: { get: (k: string) => k === 'roomCode' ? ROOM_CODE : null },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JoinComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ---- Initial render ----

  it('reads roomCode from ActivatedRoute.params on init', () => {
    expect(component.roomCode).toBe(ROOM_CODE);
  });

  it('does not render the room code as visible text in the template', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    // Room code may appear in hidden inputs but should not be prominently displayed
    const text = compiled.textContent ?? '';
    // The room code should not appear as standalone visible text
    // We check it's not in a heading or label
    const headings = Array.from(compiled.querySelectorAll('h1, h2, h3, label, p'))
      .map(el => el.textContent ?? '');
    const headingContainsCode = headings.some(t => t.includes(ROOM_CODE));
    expect(headingContainsCode).toBeFalse();
  });

  it('displays the Promptle logo', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Promptle');
  });

  it('displays The AI Telephone tagline', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('The AI Telephone');
  });

  // ---- "Continue to Room" button ----

  it('calls RoomApiService.joinRoom with room code from route', () => {
    roomApiSpy.joinRoom.and.returnValue(of({
      playerToken: 'tok',
      roomCode: ROOM_CODE,
      playerId: 'pid',
    }));
    playerServiceSpy.saveToLocalStorage.and.stub();
    routerSpy.navigate.and.stub();

    component.alias.set('Bob');
    component.onJoinRoom();

    expect(roomApiSpy.joinRoom).toHaveBeenCalledWith(
      ROOM_CODE,
      'Bob',
      jasmine.any(String)
    );
  });

  it('saves to localStorage and navigates to /lobby/{roomCode} on success', () => {
    roomApiSpy.joinRoom.and.returnValue(of({
      playerToken: 'tok-abc',
      roomCode: ROOM_CODE,
      playerId: 'pid-xyz',
    }));
    playerServiceSpy.saveToLocalStorage.and.stub();
    routerSpy.navigate.and.stub();

    component.alias.set('Bob');
    component.onJoinRoom();

    expect(playerServiceSpy.saveToLocalStorage).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  it('displays "Room is full" error when API returns 409 with "full" message', () => {
    const errorResponse = { status: 409, error: { error: 'Room is full' } };
    roomApiSpy.joinRoom.and.returnValue(throwError(() => errorResponse));

    component.alias.set('Bob');
    component.onJoinRoom();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('full');
  });

  it('displays "Game already in progress" error when API returns 409 with "in progress" message', () => {
    const errorResponse = { status: 409, error: { error: 'Game already in progress' } };
    roomApiSpy.joinRoom.and.returnValue(throwError(() => errorResponse));

    component.alias.set('Bob');
    component.onJoinRoom();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('progress');
  });
});
