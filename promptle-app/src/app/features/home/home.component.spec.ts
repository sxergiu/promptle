import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';

import { HomeComponent } from './home.component';
import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';

describe('HomeComponent', () => {
  let component: HomeComponent;
  let fixture: ComponentFixture<HomeComponent>;
  let roomApiSpy: jasmine.SpyObj<RoomApiService>;
  let playerServiceSpy: jasmine.SpyObj<PlayerService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const SUBMIT = { alias: 'Alice', avatarId: 'icon-1' };

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

  it('renders the shared player-setup screen', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-player-setup')).not.toBeNull();
    expect(compiled.textContent).toContain('PROMPTLE');
  });

  it('calls createRoom with the submitted alias and avatar', () => {
    roomApiSpy.createRoom.and.returnValue(of({ roomCode: 'ABCD1234', playerToken: 'tok', playerId: 'pid' }));

    component.onCreateRoom(SUBMIT);

    expect(roomApiSpy.createRoom).toHaveBeenCalledWith('Alice', 'icon-1');
  });

  it('saves to localStorage and navigates to the lobby on success', () => {
    const roomCode = 'ABCD1234';
    roomApiSpy.createRoom.and.returnValue(of({ roomCode, playerToken: 'tok', playerId: 'pid' }));

    component.onCreateRoom(SUBMIT);

    expect(playerServiceSpy.saveToLocalStorage).toHaveBeenCalledWith(
      roomCode,
      jasmine.objectContaining({ alias: 'Alice', avatarId: 'icon-1' }),
    );
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/lobby', roomCode]);
  });
});
