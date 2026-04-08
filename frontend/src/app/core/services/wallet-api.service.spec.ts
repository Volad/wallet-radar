import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  AddSessionRequest,
  DeleteIntegrationResponse,
  PutSessionSettingsRequest,
  RebuildSessionTransactionsResponse,
  SessionBackfillStatusResponse,
  SessionDashboardResponse,
  SessionSettingsResponse,
  SessionTransactionsResponse,
  UpsertBybitIntegrationResponse,
} from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

describe('WalletApiService', () => {
  const sessionsBaseUrl = '/api/v1/sessions';

  let service: WalletApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [WalletApiService],
    });

    service = TestBed.inject(WalletApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('posts session payload to /sessions', () => {
    const payload: AddSessionRequest = {
      sessionId: '549b0aba-a9af-4789-b125-ebb86314a3f1',
      wallets: [
        {
          address: '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f',
          label: 'Wallet 1',
          color: '#22d3ee',
          networks: ['ETHEREUM', 'ARBITRUM'],
        },
      ],
    };

    let responseMessage: string | undefined;
    service.addSession(payload).subscribe((response) => {
      responseMessage = response.message;
    });

    const req = httpMock.expectOne(`${sessionsBaseUrl}`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush({
      sessionId: payload.sessionId,
      message: 'Session saved, backfill started',
    });

    expect(responseMessage).toBe('Session saved, backfill started');
  });

  it('gets session backfill status from /sessions/{id}/backfill-status', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const mockResponse: SessionBackfillStatusResponse = {
      sessionId,
      status: 'RUNNING',
      overallProgressPct: 35,
      totalTargets: 8,
      completedTargets: 2,
      wallets: [
        {
          address: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
          label: 'Wallet 1',
          color: '#22d3ee',
          networks: [
            {
              networkId: 'ETHEREUM',
              status: 'RUNNING',
              progressPct: 35,
              lastBlockSynced: 12345,
              backfillComplete: false,
              syncBannerMessage: 'Backfill queued',
            },
          ],
        },
      ],
    };

    service.getSessionBackfillStatus(sessionId).subscribe((response) => {
      expect(response.sessionId).toBe(mockResponse.sessionId);
      expect(response.overallProgressPct).toBe(mockResponse.overallProgressPct);
      expect(response.wallets.length).toBe(1);
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/backfill-status`
    );
    expect(req.request.method).toBe('GET');
    req.flush(mockResponse);

    // assertions are performed in subscription callback
  });

  it('gets session dashboard from /sessions/{id}/dashboard', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionDashboardResponse = {
      sessionId,
      summary: {
        portfolioValueUsd: 123.45,
        totalUnrealizedPnlUsd: 10,
        totalUnrealizedPnlPct: 8.8,
        totalRealizedPnlUsd: 3.2,
      },
      wallets: [],
      tokenPositions: [],
    };

    service.getSessionDashboard(sessionId).subscribe((result) => {
      expect(result.summary.portfolioValueUsd).toBe(123.45);
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/dashboard`
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });

  it('triggers session transactions rebuild via /sessions/{id}/transactions/rebuild', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: RebuildSessionTransactionsResponse = {
      sessionId,
      projectedTransactions: 12,
      message: 'Session transactions rebuilt',
    };

    service.rebuildSessionTransactions(sessionId).subscribe((result) => {
      expect(result.projectedTransactions).toBe(12);
      expect(result.message).toBe('Session transactions rebuilt');
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/transactions/rebuild`
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(response);
  });

  it('gets session transactions from /sessions/{id}/transactions with limit', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionTransactionsResponse = {
      sessionId,
      offset: 25,
      limit: 10,
      totalCount: 101,
      hasMore: true,
      items: [
        {
          id: 's-1',
          sourceType: 'CHAIN',
          txHash: '0xabc',
          networkId: 'BSC',
          walletAddress: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
          matchedCounterparty: 'BYBIT:33625378',
          blockTimestamp: '2026-03-01T10:00:00Z',
          type: 'SWAP',
          status: 'CONFIRMED',
          issue: null,
          bridgeStatus: null,
          realisedPnlUsdTotal: 1.23,
          avcoSnapshotVersion: null,
          flows: [],
        },
      ],
    };

    service.getSessionTransactions(sessionId, {
      limit: 10,
      offset: 25,
      search: 'eth',
      bridgeStatus: 'MATCHED',
      spamFilter: 'SPAM_ONLY',
      walletIds: ['0xwallet-a'],
      networkIds: ['BSC'],
    }).subscribe((result) => {
      expect(result.items.length).toBe(1);
      expect(result.items[0].txHash).toBe('0xabc');
      expect(result.totalCount).toBe(101);
    });

    const req = httpMock.expectOne(
      (request) =>
        request.url ===
          `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/transactions` &&
        request.params.get('limit') === '10' &&
        request.params.get('offset') === '25' &&
        request.params.get('search') === 'eth' &&
        request.params.get('bridgeStatus') === 'MATCHED' &&
        request.params.get('spamFilter') === 'SPAM_ONLY' &&
        request.params.getAll('walletId')?.includes('0xwallet-a') === true &&
        request.params.getAll('networkId')?.includes('BSC') === true
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });

  it('gets session settings from /sessions/{id}/settings', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionSettingsResponse = {
      sessionId,
      wallets: [],
      integrations: [
        {
          integrationId: 'BYBIT-33625378',
          provider: 'BYBIT',
          status: 'READY',
          displayName: 'Bybit main',
          accountRef: 'BYBIT:33625378',
          maskedKey: 'abcd...1234',
          readOnly: true,
          capabilities: ['ASSET'],
          lastValidatedAt: '2026-04-07T10:00:00Z',
          lastSyncAt: '2026-04-07T10:05:00Z',
          lastError: null,
          totalSegments: 7,
          completedSegments: 7,
          failedSegments: 0,
          progressPct: 100,
        },
      ],
      hideSmallAssets: true,
      showReconciliationWarnings: true,
    };

    service.getSessionSettings(sessionId).subscribe((result) => {
      expect(result.integrations[0].accountRef).toBe('BYBIT:33625378');
      expect(result.hideSmallAssets).toBeTrue();
      expect(result.showReconciliationWarnings).toBeTrue();
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/settings`
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });

  it('puts session settings to /sessions/{id}/settings', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const payload: PutSessionSettingsRequest = {
      wallets: [
        {
          address: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
          label: 'Main',
          color: '#22d3ee',
          networks: ['ETHEREUM', 'ARBITRUM'],
        },
      ],
      integrations: [
        {
          provider: 'BYBIT',
          displayName: 'Bybit main',
          apiKey: '',
          apiSecret: '',
        },
      ],
      hideSmallAssets: true,
      showReconciliationWarnings: false,
    };
    const response: SessionSettingsResponse = {
      sessionId,
      wallets: payload.wallets,
      integrations: [],
      hideSmallAssets: true,
      showReconciliationWarnings: false,
    };

    service.putSessionSettings(sessionId, payload).subscribe((result) => {
      expect(result.hideSmallAssets).toBeTrue();
      expect(result.showReconciliationWarnings).toBeFalse();
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/settings`
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush(response);
  });

  it('upserts Bybit integration via /sessions/{id}/integrations/bybit', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const payload = {
      displayName: 'Bybit main',
      apiKey: 'api-key',
      apiSecret: 'secret',
    };
    const response: UpsertBybitIntegrationResponse = {
      integrationId: 'BYBIT-33625378',
      provider: 'BYBIT',
      status: 'BACKFILLING',
      displayName: 'Bybit main',
      accountRef: 'BYBIT:33625378',
      maskedKey: 'api-...key',
      message: 'Bybit integration saved, backfill planned',
    };

    service.upsertBybitIntegration(sessionId, payload).subscribe((result) => {
      expect(result.integrationId).toBe('BYBIT-33625378');
      expect(result.status).toBe('BACKFILLING');
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/integrations/bybit`
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush(response);
  });

  it('deletes integration via /sessions/{id}/integrations/{integrationId}', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const integrationId = 'BYBIT-33625378';
    const response: DeleteIntegrationResponse = {
      integrationId,
      message: 'Integration removed',
    };

    service.deleteIntegration(sessionId, integrationId).subscribe((result) => {
      expect(result.integrationId).toBe(integrationId);
      expect(result.message).toBe('Integration removed');
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/integrations/${encodeURIComponent(integrationId)}`
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(response);
  });
});
