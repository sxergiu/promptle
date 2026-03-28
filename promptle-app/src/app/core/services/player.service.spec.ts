import { TestBed } from '@angular/core/testing';
import { PlayerService } from './player.service';

describe('PlayerService (new localStorage API)', () => {
  let service: PlayerService;

  const TEST_ROOM_CODE = 'test-room';
  const STORAGE_KEY = `promptle_player_${TEST_ROOM_CODE}`;

  const TEST_DATA = {
    playerToken: 'token-abc-123',
    playerId: 'player-id-456',
    alias: 'Alice',
    avatarId: 'icon-3',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PlayerService);
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  // ---- saveToLocalStorage ----

  describe('saveToLocalStorage()', () => {
    it('writes JSON-encoded object to the correct localStorage key', () => {
      service.saveToLocalStorage(TEST_ROOM_CODE, TEST_DATA);

      const raw = localStorage.getItem(STORAGE_KEY);
      expect(raw).not.toBeNull();
      const parsed = JSON.parse(raw!);
      expect(parsed.playerToken).toBe(TEST_DATA.playerToken);
      expect(parsed.playerId).toBe(TEST_DATA.playerId);
      expect(parsed.alias).toBe(TEST_DATA.alias);
      expect(parsed.avatarId).toBe(TEST_DATA.avatarId);
    });
  });

  // ---- loadFromLocalStorage ----

  describe('loadFromLocalStorage()', () => {
    it('returns parsed object when key exists', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(TEST_DATA));

      const result = service.loadFromLocalStorage(TEST_ROOM_CODE);

      expect(result).not.toBeNull();
      expect(result!.playerToken).toBe(TEST_DATA.playerToken);
      expect(result!.playerId).toBe(TEST_DATA.playerId);
      expect(result!.alias).toBe(TEST_DATA.alias);
      expect(result!.avatarId).toBe(TEST_DATA.avatarId);
    });

    it('returns null when key is absent', () => {
      const result = service.loadFromLocalStorage(TEST_ROOM_CODE);
      expect(result).toBeNull();
    });
  });

  // ---- clearLocalStorage ----

  describe('clearLocalStorage()', () => {
    it('removes the localStorage entry for the room code', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(TEST_DATA));

      service.clearLocalStorage(TEST_ROOM_CODE);

      expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('does not throw when the key does not exist (idempotent)', () => {
      expect(() => service.clearLocalStorage(TEST_ROOM_CODE)).not.toThrow();
    });
  });

  // ---- Signal state ----

  describe('signals', () => {
    it('playerToken signal reflects value after loadFromLocalStorage', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(TEST_DATA));

      service.loadFromLocalStorage(TEST_ROOM_CODE);

      expect(service.playerToken()).toBe(TEST_DATA.playerToken);
    });

    it('playerId signal updates when saveToLocalStorage is called', () => {
      service.saveToLocalStorage(TEST_ROOM_CODE, TEST_DATA);

      expect(service.playerId()).toBe(TEST_DATA.playerId);
    });
  });
});
