// sockjs-client references Node.js `global` — polyfill it for the browser/test environment
(window as any)['global'] = window;

import { TestBed, TestModuleMetadata } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { BrowserTestingModule, platformBrowserTesting } from '@angular/platform-browser/testing';

TestBed.initTestEnvironment(BrowserTestingModule, platformBrowserTesting(), {
  errorOnUnknownElements: true,
  errorOnUnknownProperties: true,
});

// Intercept every TestBed.configureTestingModule call and inject provideZonelessChangeDetection()
// into the providers array. This ensures zoneless mode is active for all specs, including those
// that define their own providers without explicitly including the zoneless provider.
const _origConfig = TestBed.configureTestingModule.bind(TestBed);
TestBed.configureTestingModule = (moduleDef: TestModuleMetadata) => {
  return _origConfig({
    ...moduleDef,
    providers: [...(moduleDef.providers ?? []), provideZonelessChangeDetection()],
  });
};
