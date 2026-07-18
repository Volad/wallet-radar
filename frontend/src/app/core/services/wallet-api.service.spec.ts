import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  AddSessionRequest,
  DeleteIntegrationResponse,
  PutSessionSettingsRequest,
  RebuildSessionTransactionsResponse,
  SessionBackfillStatusResponse,
  SessionDashboardResponse,
  SessionLendingResponse,
  SessionLpPositionResponse,
  SessionLpResponse,
  SessionRefreshResponse,
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
      message: 'Session saved, universe sync scheduled',
    });

    expect(responseMessage).toBe('Session saved, universe sync scheduled');
  });

  it('gets session backfill status from /sessions/{id}/backfill-status', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const mockResponse: SessionBackfillStatusResponse = {
      sessionId,
      status: 'RUNNING',
      acquisitionStatus: 'RUNNING',
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
        netExternalCapitalUsd: null,
        lifetimeExternalInflowUsd: null,
        markToMarketUsd: null,
        expectedPnlUsd: null,
        reportedPnlUsd: null,
        conservationDeltaUsd: null,
        conservationThresholdUsd: null,
        conservationBreached: null,
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

  it('gets session lending from /sessions/{id}/lending', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionLendingResponse = {
      sessionId,
      summary: {
        totalSuppliedUsd: 1000,
        totalBorrowedUsd: 250,
        netExposureUsd: 750,
        openGroups: 1,
        closedGroups: 0,
        protocols: 1,
      },
      groups: [
        {
          id: 'aave:mantle:0x1234',
          protocol: 'Aave',
          networkId: 'MANTLE',
          walletAddress: '0x1234',
          status: 'OPEN',
          healthFactor: 3.12,
          healthLabel: 'Safe',
          healthProgress: 100,
          healthStatus: 'ESTIMATED',
          healthSource: 'ACCOUNTING_ESTIMATE',
          healthStale: false,
          supplyUsd: 1000,
          borrowUsd: 250,
          netExposureUsd: 750,
          positions: [],
          cycles: [],
          history: [],
        },
      ],
    };

    service.getSessionLending(sessionId).subscribe((result) => {
      expect(result.summary.netExposureUsd).toBe(750);
      expect(result.groups[0].protocol).toBe('Aave');
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/lending`
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });

  it('gets session LP from /sessions/{id}/lp', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionLpResponse = {
      sessionId,
      summary: {
        activeTvlUsd: 4991,
        feesEarnedUsd: 223.6,
        unclaimedUsd: 41.2,
        inRange: 1,
        outOfRange: 0,
        realizedPnlUsd: null,
      },
      positions: [],
    };

    service.getSessionLp(sessionId).subscribe((result) => {
      expect(result.summary.activeTvlUsd).toBe(4991);
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/lp?scope=active`
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });

  it('posts LP position refresh to /sessions/{id}/lp/positions/{correlationId}/refresh', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const correlationId = 'uni-eth-usdc';
    const response: SessionLpPositionResponse = {
      correlationId,
      protocol: 'Uniswap V3',
      family: 'CL_NFT',
      networkId: 'ETHEREUM',
      wallet: '0x1234',
      pair: 'ETH / USDC',
      token0: { symbol: 'ETH', quantity: 1, valueUsd: 3000 },
      token1: { symbol: 'USDC', quantity: 3000, valueUsd: 3000 },
      feeTierPct: 0.05,
      tokenId: '#1',
      status: 'in_range',
      staked: false,
      range: null,
      tvlUsd: { valueUsd: 6000, precision: 'EXACT' },
      costBasisUsd: 5500,
      withdrawnUsd: 0,
      fees: { claimedUsd: 10, unclaimedUsd: 2, precision: 'EXACT', perToken: [] },
      il: { pct: -1, usd: -50, precision: 'ESTIMATED' },
      priceAppreciationUsd: 20,
      netPnlUsd: 30,
      accountingUnrealizedUsd: 30,
      apr: { now: 10, avg: 12, precision: 'ESTIMATED' },
      earningsDaily: [],
      aprDaily: [],
      txns: [],
      enteredAt: '2024-10-12T00:00:00Z',
      closedAt: null,
      snapshotAt: '2026-06-24T12:00:00Z',
      snapshotStale: false,
      trackingStartedAt: '2024-10-12T00:00:00Z',
    };

    service.refreshLpPosition(sessionId, correlationId).subscribe((result) => {
      expect(result.correlationId).toBe(correlationId);
      const tvl = result.tvlUsd;
      const valueUsd = typeof tvl === 'number' ? tvl : tvl?.valueUsd;
      expect(valueUsd).toBe(6000);
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/lp/positions/${encodeURIComponent(correlationId)}/refresh`
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(response);
  });

  it('posts session refresh to /sessions/{id}/refresh', () => {
    const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
    const response: SessionRefreshResponse = {
      sessionId,
      status: 'SCHEDULED',
      scheduledTargets: 2,
      skippedTargets: 0,
      message: 'Incremental refresh queued',
    };

    service.refreshSession(sessionId).subscribe((result) => {
      expect(result.status).toBe('SCHEDULED');
      expect(result.scheduledTargets).toBe(2);
    });

    const req = httpMock.expectOne(
      `${sessionsBaseUrl}/${encodeURIComponent(sessionId)}/refresh`
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
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
          color: null,
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
          streamSync: [],
        },
      ],
      externalVenues: [],
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
      externalVenues: [],
      hideSmallAssets: true,
      showReconciliationWarnings: false,
    };
    const response: SessionSettingsResponse = {
      sessionId,
      wallets: payload.wallets,
      integrations: [],
      externalVenues: [],
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
      message: 'Bybit integration saved, universe sync scheduled',
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
