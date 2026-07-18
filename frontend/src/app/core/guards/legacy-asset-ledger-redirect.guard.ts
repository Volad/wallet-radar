import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { SessionStorageService } from '../services/session-storage.service';

/**
 * Redirects legacy deep links `/sessions/:sessionId/assets/:familyIdentity`
 * to `/move-basis/:familyIdentity`, persisting sessionId in storage when present.
 */
export const legacyAssetLedgerRedirectGuard: CanActivateFn = (route) => {
  const router = inject(Router);
  const sessionStorage = inject(SessionStorageService);

  const sessionId = route.paramMap.get('sessionId')?.trim() ?? '';
  const familyIdentity = route.paramMap.get('familyIdentity')?.trim() ?? '';

  if (sessionId.length > 0) {
    sessionStorage.setSessionId(sessionId);
  }

  if (familyIdentity.length === 0) {
    return router.parseUrl('/');
  }

  return router.createUrlTree(['/move-basis', familyIdentity]);
};
