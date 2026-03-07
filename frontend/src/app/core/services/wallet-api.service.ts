import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  AddSessionRequest,
  AddSessionResponse,
  RebuildSessionTransactionsResponse,
  SessionBackfillStatusResponse,
  SessionResponse,
  SessionTransactionsResponse,
} from '../models/wallet-api.models';

@Injectable({ providedIn: 'root' })
export class WalletApiService {
  private readonly httpClient = inject(HttpClient);
  private readonly walletsEndpoint = `${environment.apiBaseUrl}/wallets`;
  private readonly sessionsEndpoint = `${environment.apiBaseUrl}/sessions`;

  addSession(payload: AddSessionRequest): Observable<AddSessionResponse> {
    return this.httpClient.post<AddSessionResponse>(this.sessionsEndpoint, payload);
  }

  getSession(sessionId: string): Observable<SessionResponse> {
    return this.httpClient.get<SessionResponse>(`${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}`);
  }

  getSessionBackfillStatus(sessionId: string): Observable<SessionBackfillStatusResponse> {
    return this.httpClient.get<SessionBackfillStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/backfill-status`
    );
  }

  rebuildSessionTransactions(sessionId: string): Observable<RebuildSessionTransactionsResponse> {
    return this.httpClient.post<RebuildSessionTransactionsResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/transactions/rebuild`,
      {}
    );
  }

  getSessionTransactions(
    sessionId: string,
    limit = 50
  ): Observable<SessionTransactionsResponse> {
    return this.httpClient.get<SessionTransactionsResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/transactions`,
      { params: { limit } }
    );
  }

  addWallets(payload: AddSessionRequest): Observable<AddSessionResponse> {
    // Compatibility shim while UI migrates to /sessions.
    return this.httpClient.post<AddSessionResponse>(this.walletsEndpoint, payload);
  }
}
