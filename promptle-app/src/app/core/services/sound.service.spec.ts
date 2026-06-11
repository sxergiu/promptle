import { TestBed } from '@angular/core/testing';
import { SoundService } from './sound.service';

describe('SoundService', () => {
  let service: SoundService;

  /** Stub the AudioContext so tone()/sequence() can be observed without real audio. */
  let createOscillator: jasmine.Spy;

  beforeEach(() => {
    localStorage.removeItem('promptle_muted');

    const osc = {
      type: 'sine',
      frequency: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} },
      connect: () => ({ connect: () => {} }),
      start: () => {},
      stop: () => {},
    };
    createOscillator = jasmine.createSpy('createOscillator').and.returnValue(osc);
    const gain = {
      gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} },
      connect: () => ({ connect: () => {} }),
    };
    (window as unknown as { AudioContext: unknown }).AudioContext = function () {
      return { currentTime: 0, state: 'running', resume: () => Promise.resolve(),
               createOscillator, createGain: () => gain, destination: {} };
    };

    TestBed.configureTestingModule({});
    service = TestBed.inject(SoundService);
  });

  afterEach(() => localStorage.removeItem('promptle_muted'));

  it('starts unmuted by default', () => {
    expect(service.muted()).toBeFalse();
  });

  it('toggle() flips the signal and persists to localStorage', () => {
    service.toggle();
    expect(service.muted()).toBeTrue();
    expect(localStorage.getItem('promptle_muted')).toBe('1');

    service.toggle();
    expect(service.muted()).toBeFalse();
    expect(localStorage.getItem('promptle_muted')).toBe('0');
  });

  it('restores the muted preference from localStorage', () => {
    localStorage.setItem('promptle_muted', '1');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const restored = TestBed.inject(SoundService);
    expect(restored.muted()).toBeTrue();
  });

  it('plays when unmuted', () => {
    service.tone({ startHz: 440 });
    expect(createOscillator).toHaveBeenCalled();
  });

  it('is silent when muted — the global gate governs every sound', () => {
    service.toggle(); // -> muted
    service.tone({ startHz: 440 });
    service.sequence([{ startHz: 440 }, { startHz: 550, delaySec: 0.1 }]);
    expect(createOscillator).not.toHaveBeenCalled();
  });
});
