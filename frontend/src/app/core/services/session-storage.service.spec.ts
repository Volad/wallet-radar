import { TestBed } from '@angular/core/testing';

import { SessionStorageService } from './session-storage.service';

describe('SessionStorageService', () => {
  const storageKey = 'wr.sessionId';
  const validSessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';

  let service: SessionStorageService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [SessionStorageService],
    });
    localStorage.clear();
    service = TestBed.inject(SessionStorageService);
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('persists and returns valid sessionId', () => {
    service.setSessionId(validSessionId);
    expect(localStorage.getItem(storageKey)).toBe(validSessionId);
    expect(service.getSessionId()).toBe(validSessionId);
  });

  it('returns null and clears invalid stored value', () => {
    localStorage.setItem(storageKey, 'invalid-session-id');
    expect(service.getSessionId()).toBeNull();
    expect(localStorage.getItem(storageKey)).toBeNull();
  });

  it('clearSessionId removes session from storage', () => {
    localStorage.setItem(storageKey, validSessionId);
    service.clearSessionId();
    expect(localStorage.getItem(storageKey)).toBeNull();
  });
});
