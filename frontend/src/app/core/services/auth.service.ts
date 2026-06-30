import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap, map } from 'rxjs';

import { AuthMeResponse } from '../models/auth.models';
import { SessionStorageService } from './session-storage.service';
import { WalletApiService } from './wallet-api.service';

/**
 * Manages the Google SSO authentication lifecycle.
 *
 * On app startup, call {@link checkAuth} to determine if the user is authenticated.
 * If authenticated, the canonical sessionId from the backend is stored in
 * {@link SessionStorageService} (backend is the source of truth, not localStorage UUID).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);

  readonly authState = signal<AuthMeResponse>({ authenticated: false });
  /** True after the initial checkAuth() call completes (success or error). */
  readonly isAuthChecked = signal(false);

  /** Called once at application startup to resolve auth state. */
  checkAuth(): Observable<AuthMeResponse> {
    return this.walletApiService.authMe().pipe(
      tap((response) => {
        this.authState.set(response);
        if (response.authenticated && response.sessionId) {
          this.sessionStorageService.setSessionId(response.sessionId);
        } else {
          // Not authenticated — ignore any stale localStorage UUID.
          this.sessionStorageService.clearSessionId();
        }
        this.isAuthChecked.set(true);
      }),
    );
  }

  /** Redirects browser to the Google OAuth2 Authorization Code flow. */
  loginWithGoogle(): void {
    window.location.href = '/oauth2/authorization/google';
  }

  /** Calls the logout endpoint to clear the wr_auth cookie, then resets local state. */
  logout(): Observable<void> {
    return this.walletApiService.logout().pipe(
      tap(() => {
        this.authState.set({ authenticated: false });
        this.sessionStorageService.clearSessionId();
      }),
      map(() => undefined),
    );
  }
}
