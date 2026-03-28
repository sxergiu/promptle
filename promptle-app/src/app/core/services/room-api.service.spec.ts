import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { RoomApiService } from './room-api.service';

describe('RoomApiService', () => {
  let service: RoomApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [RoomApiService],
    });
    service = TestBed.inject(RoomApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ---- createRoom ----

  describe('createRoom()', () => {
    it('issues POST /api/rooms with alias and avatarId in body', () => {
      service.createRoom('Alice', 'icon-1').subscribe();

      const req = httpMock.expectOne('/api/rooms');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ alias: 'Alice', avatarId: 'icon-1' });

      req.flush({ roomCode: 'A1B2C3D4' });
    });

    it('emits roomCode from the server response', () => {
      let result: { roomCode: string } | undefined;
      service.createRoom('Alice', 'icon-1').subscribe((r) => (result = r));

      const req = httpMock.expectOne('/api/rooms');
      req.flush({ roomCode: 'A1B2C3D4' });

      expect(result?.roomCode).toBe('A1B2C3D4');
    });
  });

  // ---- joinRoom ----

  describe('joinRoom()', () => {
    it('issues POST /api/rooms/{roomCode}/join with alias and avatarId', () => {
      service.joinRoom('ROOM1234', 'Bob', 'icon-2').subscribe();

      const req = httpMock.expectOne('/api/rooms/ROOM1234/join');
      expect(req.request.method).toBe('POST');
      expect(req.request.url).toContain('ROOM1234');
      expect(req.request.body).toEqual({ alias: 'Bob', avatarId: 'icon-2' });

      req.flush({
        playerToken: 'tok-123',
        roomCode: 'ROOM1234',
        playerId: 'pid-456',
      });
    });

    it('emits JoinRoomResponse with all three fields on success', () => {
      let result: any;
      service.joinRoom('ROOM1234', 'Bob', 'icon-2').subscribe((r) => (result = r));

      const req = httpMock.expectOne('/api/rooms/ROOM1234/join');
      req.flush({
        playerToken: 'tok-abc',
        roomCode: 'ROOM1234',
        playerId: 'pid-xyz',
      });

      expect(result.playerToken).toBe('tok-abc');
      expect(result.roomCode).toBe('ROOM1234');
      expect(result.playerId).toBe('pid-xyz');
    });

    it('passes through 409 error so the component can display inline errors', () => {
      let error: any;
      service.joinRoom('ROOM1234', 'Bob', 'icon-2').subscribe({
        next: () => {},
        error: (e) => (error = e),
      });

      const req = httpMock.expectOne('/api/rooms/ROOM1234/join');
      req.flush({ error: 'Room is full' }, { status: 409, statusText: 'Conflict' });

      expect(error).toBeDefined();
      expect(error.status).toBe(409);
    });
  });
});
