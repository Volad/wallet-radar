import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  AddSessionRequest,
  AddSessionResponse,
  DeleteIntegrationResponse,
  GetSessionTransactionsRequest,
  PutSessionSettingsRequest,
  RebuildSessionTransactionsResponse,
  SessionAssetLedgerResponse,
  SessionBackfillStatusResponse,
  SessionDashboardResponse,
  SessionSettingsResponse,
  SessionResponse,
  SessionTransactionsResponse,
  UpsertBybitIntegrationRequest,
  UpsertBybitIntegrationResponse,
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

  getSessionSettings(sessionId: string): Observable<SessionSettingsResponse> {
    return this.httpClient.get<SessionSettingsResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/settings`
    );
  }

  putSessionSettings(sessionId: string, payload: PutSessionSettingsRequest): Observable<SessionSettingsResponse> {
    return this.httpClient.put<SessionSettingsResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/settings`,
      payload
    );
  }

  upsertBybitIntegration(
    sessionId: string,
    payload: UpsertBybitIntegrationRequest
  ): Observable<UpsertBybitIntegrationResponse> {
    return this.httpClient.put<UpsertBybitIntegrationResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/integrations/bybit`,
      payload
    );
  }

  deleteIntegration(sessionId: string, integrationId: string): Observable<DeleteIntegrationResponse> {
    return this.httpClient.delete<DeleteIntegrationResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/integrations/${encodeURIComponent(integrationId)}`
    );
  }

  getSessionBackfillStatus(sessionId: string): Observable<SessionBackfillStatusResponse> {
    return this.httpClient.get<SessionBackfillStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/backfill-status`
    );
  }

  getSessionDashboard(sessionId: string): Observable<SessionDashboardResponse> {
    return this.httpClient.get<SessionDashboardResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/dashboard`
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
    request: GetSessionTransactionsRequest = {}
  ): Observable<SessionTransactionsResponse> {
    let params = new HttpParams()
      .set('limit', String(request.limit ?? 50))
      .set('offset', String(request.offset ?? 0));
    if (request.search !== undefined && request.search !== null && request.search.trim().length > 0) {
      params = params.set('search', request.search.trim());
    }
    if (request.bridgeStatus !== undefined && request.bridgeStatus !== null) {
      params = params.set('bridgeStatus', request.bridgeStatus);
    }
    if (request.spamFilter !== undefined && request.spamFilter !== null) {
      params = params.set('spamFilter', request.spamFilter);
    }
    for (const walletId of request.walletIds ?? []) {
      params = params.append('walletId', walletId);
    }
    for (const networkId of request.networkIds ?? []) {
      params = params.append('networkId', networkId);
    }

    return this.httpClient.get<SessionTransactionsResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/transactions`,
      { params }
    );
  }

  getSessionAssetLedger(
    sessionId: string,
    familyIdentity: string
  ): Observable<SessionAssetLedgerResponse> {
    return this.httpClient.get<SessionAssetLedgerResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/asset-ledger`,
      {
        params: {
          familyIdentity,
        },
      }
    );
  }

  addWallets(payload: AddSessionRequest): Observable<AddSessionResponse> {
    // Compatibility shim while UI migrates to /sessions.
    return this.httpClient.post<AddSessionResponse>(this.walletsEndpoint, payload);
  }
}
