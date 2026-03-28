import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, UrlTree } from '@angular/router';
import { playerTokenGuard } from './player-token.guard';

describe('playerTokenGuard', () => {
  let router: jasmine.SpyObj<Router>;

  const ROOM_CODE = 'ABCD1234';

  beforeEach(() => {
    router = jasmine.createSpyObj('Router', ['createUrlTree']);

    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
      ],
    });

    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  function buildRoute(roomCode: string): ActivatedRouteSnapshot {
    return {
      paramMap: { get: (key: string) => key === 'roomCode' ? roomCode : null },
    } as any;
  }

  it('returns true when localStorage key exists for the room code', () => {
    localStorage.setItem(`promptle_player_${ROOM_CODE}`, JSON.stringify({ playerToken: 'tok' }));

    const result = TestBed.runInInjectionContext(() =>
      playerTokenGuard(buildRoute(ROOM_CODE), {} as any)
    );

    expect(result).toBeTrue();
  });

  it('returns UrlTree redirecting to /join/{roomCode} when no token in localStorage', () => {
    const mockUrlTree = {} as UrlTree;
    router.createUrlTree.and.returnValue(mockUrlTree);

    const result = TestBed.runInInjectionContext(() =>
      playerTokenGuard(buildRoute(ROOM_CODE), {} as any)
    );

    expect(router.createUrlTree).toHaveBeenCalledWith(['/join', ROOM_CODE]);
    expect(result).toBe(mockUrlTree);
  });

  it('uses roomCode from route paramMap as the localStorage key suffix', () => {
    const otherRoomCode = 'ZZZZ9999';
    localStorage.setItem(`promptle_player_${otherRoomCode}`, JSON.stringify({ playerToken: 'tok' }));

    const resultForOtherRoom = TestBed.runInInjectionContext(() =>
      playerTokenGuard(buildRoute(otherRoomCode), {} as any)
    );
    expect(resultForOtherRoom).toBeTrue();

    const mockUrlTree = {} as UrlTree;
    router.createUrlTree.and.returnValue(mockUrlTree);

    const resultForNoRoom = TestBed.runInInjectionContext(() =>
      playerTokenGuard(buildRoute(ROOM_CODE), {} as any)
    );
    expect(resultForNoRoom).toBe(mockUrlTree);
    expect(router.createUrlTree).toHaveBeenCalledWith(['/join', ROOM_CODE]);
  });
});
