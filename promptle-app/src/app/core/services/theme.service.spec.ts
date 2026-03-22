import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ThemeService);
    // Reset theme attribute before each test
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.removeAttribute('data-bs-theme');
  });

  afterEach(() => {
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.removeAttribute('data-bs-theme');
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('toggle() — dark toggle', () => {
    it('sets data-theme to "dark" when current theme is light', () => {
      document.documentElement.setAttribute('data-theme', 'light');

      service.toggle();

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });
  });

  describe('toggle() — light toggle', () => {
    it('sets data-theme to "light" or removes attribute when current theme is dark', () => {
      document.documentElement.setAttribute('data-theme', 'dark');

      service.toggle();

      const value = document.documentElement.getAttribute('data-theme');
      const isLightOrAbsent = value === 'light' || value === null;
      expect(isLightOrAbsent).toBeTrue();
    });
  });

  describe('legacy attribute', () => {
    it('never writes data-bs-theme after toggling', () => {
      service.toggle();
      expect(document.documentElement.hasAttribute('data-bs-theme')).toBeFalse();

      service.toggle();
      expect(document.documentElement.hasAttribute('data-bs-theme')).toBeFalse();
    });
  });
});
