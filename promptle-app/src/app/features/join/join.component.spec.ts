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
  const SUBMIT = { alias: 'Bob', avatarId: 'icon-2' };

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

  it('reads roomCode from the route on init', () => {
    expect(component.roomCode).toBe(ROOM_CODE);
  });

  it('redirects straight to the lobby if the player is already in this room', () => {
    playerServiceSpy.loadFromLocalStorage.and.returnValue({
      playerToken: 'tok', playerId: 'pid', alias: 'Bob', avatarId: 'icon-2',
    });
    const fresh = TestBed.createComponent(JoinComponent);
    fresh.detectChanges();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  it('does not render the room code as visible text', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const headings = Array.from(compiled.querySelectorAll('h1, h2, h3, label, p'))
      .map(el => el.textContent ?? '');
    expect(headings.some(t => t.includes(ROOM_CODE))).toBeFalse();
  });

  it('calls joinRoom with the route room code and submitted player', () => {
    roomApiSpy.joinRoom.and.returnValue(of({ playerToken: 'tok', roomCode: ROOM_CODE, playerId: 'pid' }));

    component.onJoinRoom(SUBMIT);

    expect(roomApiSpy.joinRoom).toHaveBeenCalledWith(ROOM_CODE, 'Bob', 'icon-2');
  });

  it('saves to localStorage and navigates to the lobby on success', () => {
    roomApiSpy.joinRoom.and.returnValue(of({ playerToken: 'tok-abc', roomCode: ROOM_CODE, playerId: 'pid-xyz' }));

    component.onJoinRoom(SUBMIT);

    expect(playerServiceSpy.saveToLocalStorage).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', ROOM_CODE]);
  });

  it('shows "Room is full" when the API reports a full room', () => {
    roomApiSpy.joinRoom.and.returnValue(throwError(() => ({ status: 409, error: { error: 'Room is full' } })));

    component.onJoinRoom(SUBMIT);

    expect(component.errorMessage()).toBe('Room is full');
  });

  it('shows "Game already in progress" when the API reports it', () => {
    roomApiSpy.joinRoom.and.returnValue(throwError(() => ({ status: 409, error: { error: 'Game already in progress' } })));

    component.onJoinRoom(SUBMIT);

    expect(component.errorMessage()).toBe('Game already in progress');
  });

  it('clears busy state after an error so the player can retry', () => {
    roomApiSpy.joinRoom.and.returnValue(throwError(() => ({ status: 500, error: {} })));

    component.onJoinRoom(SUBMIT);

    expect(component.busy()).toBeFalse();
    expect(component.errorMessage()).toBe('An error occurred. Please try again.');
  });
});
