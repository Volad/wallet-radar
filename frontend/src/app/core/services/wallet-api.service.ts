import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthMeResponse } from '../models/auth.models';
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
  SessionLendingResponse,
  SessionLpResponse,
  SessionLpPositionResponse,
  RefreshStatusResponse,
  SessionRefreshResponse,
  SessionSettingsResponse,
  SessionResponse,
  SessionTransactionsResponse,
  TestIntegrationConnectionRequest,
  TestIntegrationConnectionResponse,
  UpsertBybitIntegrationRequest,
  UpsertBybitIntegrationResponse,
  UpsertDzengiIntegrationRequest,
  UpsertDzengiIntegrationResponse,
} from '../models/wallet-api.models';

@Injectable({ providedIn: 'root' })
export class WalletApiService {
  private readonly httpClient = inject(HttpClient);
  private readonly walletsEndpoint = `${environment.apiBaseUrl}/wallets`;
  private readonly sessionsEndpoint = `${environment.apiBaseUrl}/sessions`;
  private readonly authEndpoint = `${environment.apiBaseUrl}/auth`;

  authMe(): Observable<AuthMeResponse> {
    return this.httpClient.get<AuthMeResponse>(`${this.authEndpoint}/me`);
  }

  logout(): Observable<unknown> {
    return this.httpClient.post(`${this.authEndpoint}/logout`, {});
  }

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

  upsertDzengiIntegration(
    sessionId: string,
    payload: UpsertDzengiIntegrationRequest
  ): Observable<UpsertDzengiIntegrationResponse> {
    return this.httpClient.put<UpsertDzengiIntegrationResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/integrations/dzengi`,
      payload
    );
  }

  deleteIntegration(sessionId: string, integrationId: string): Observable<DeleteIntegrationResponse> {
    return this.httpClient.delete<DeleteIntegrationResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/integrations/${encodeURIComponent(integrationId)}`
    );
  }

  testIntegrationConnection(
    sessionId: string,
    payload: TestIntegrationConnectionRequest
  ): Observable<TestIntegrationConnectionResponse> {
    return this.httpClient.post<TestIntegrationConnectionResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/integrations/test`,
      payload
    );
  }

  getSessionBackfillStatus(sessionId: string): Observable<SessionBackfillStatusResponse> {
    return this.httpClient.get<SessionBackfillStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/backfill-status`
    );
  }

  refreshSession(sessionId: string): Observable<SessionRefreshResponse> {
    return this.httpClient.post<SessionRefreshResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/refresh`,
      {}
    );
  }

  getSessionDashboard(sessionId: string): Observable<SessionDashboardResponse> {
    return this.httpClient.get<SessionDashboardResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/dashboard`
    );
  }

  getSessionLending(sessionId: string): Observable<SessionLendingResponse> {
    return this.httpClient.get<SessionLendingResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lending`
    );
  }

  getSessionLp(sessionId: string, scope: 'active' | 'closed' | 'all' = 'active'): Observable<SessionLpResponse> {
    return this.httpClient.get<SessionLpResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lp`,
      { params: { scope } }
    );
  }

  getSessionLpPosition(
    sessionId: string,
    correlationId: string,
    scope: 'active' | 'closed' | 'all' = 'active'
  ): Observable<SessionLpPositionResponse> {
    return this.httpClient.get<SessionLpPositionResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lp/positions/${encodeURIComponent(correlationId)}`,
      { params: { scope } }
    );
  }

  getLpRefreshStatus(sessionId: string): Observable<RefreshStatusResponse> {
    return this.httpClient.get<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lp/refresh-status`
    );
  }

  refreshLpPosition(sessionId: string, correlationId: string): Observable<RefreshStatusResponse> {
    return this.httpClient.post<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lp/positions/${encodeURIComponent(correlationId)}/refresh`,
      {}
    );
  }

  refreshAllLpPositions(sessionId: string): Observable<RefreshStatusResponse> {
    return this.httpClient.post<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lp/refresh`,
      {}
    );
  }

  getLendingRefreshStatus(sessionId: string): Observable<RefreshStatusResponse> {
    return this.httpClient.get<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lending/refresh-status`
    );
  }

  refreshLendingGroup(sessionId: string, groupKey: string): Observable<RefreshStatusResponse> {
    return this.httpClient.post<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lending/groups/${encodeURIComponent(groupKey)}/refresh`,
      {}
    );
  }

  refreshAllLending(sessionId: string): Observable<RefreshStatusResponse> {
    return this.httpClient.post<RefreshStatusResponse>(
      `${this.sessionsEndpoint}/${encodeURIComponent(sessionId)}/lending/refresh`,
      {}
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
    for (const category of request.categories ?? []) {
      params = params.append('category', category);
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
