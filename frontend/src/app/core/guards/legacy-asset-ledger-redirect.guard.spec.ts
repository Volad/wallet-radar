import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { legacyAssetLedgerRedirectGuard } from './legacy-asset-ledger-redirect.guard';
import { SessionStorageService } from '../services/session-storage.service';

describe('legacyAssetLedgerRedirectGuard', () => {
  let sessionStorageSpy: jasmine.SpyObj<SessionStorageService>;
  let router: Router;

  beforeEach(async () => {
    sessionStorageSpy = jasmine.createSpyObj<SessionStorageService>('SessionStorageService', [
      'setSessionId',
    ]);

    await TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: SessionStorageService, useValue: sessionStorageSpy },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
  });

  it('persists sessionId and redirects to move-basis route', async () => {
    const result = await TestBed.runInInjectionContext(async () =>
      legacyAssetLedgerRedirectGuard(
        {
          paramMap: {
            get: (key: string) => {
              if (key === 'sessionId') {
                return 'session-123';
              }
              if (key === 'familyIdentity') {
                return 'FAMILY:ETH';
              }
              return null;
            },
          },
        } as never,
        {} as never
      )
    );

    expect(sessionStorageSpy.setSessionId).toHaveBeenCalledWith('session-123');
    expect(router.serializeUrl(result as never)).toBe('/move-basis/FAMILY%3AETH');
  });

  it('redirects to dashboard when familyIdentity is missing', async () => {
    const result = await TestBed.runInInjectionContext(async () =>
      legacyAssetLedgerRedirectGuard(
        {
          paramMap: {
            get: (key: string) => (key === 'sessionId' ? 'session-123' : null),
          },
        } as never,
        {} as never
      )
    );

    expect(router.serializeUrl(result as never)).toBe('/');
  });
});
