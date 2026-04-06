import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  AddSessionRequest,
  RebuildSessionTransactionsResponse,
  SessionBackfillStatusResponse,
  SessionDashboardResponse,
  SessionTransactionsResponse,
} from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

describe('WalletApiService', () => {
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

    const req = httpMock.expectOne('http://localhost:8080/api/v1/sessions');
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
      `http://localhost:8080/api/v1/sessions/${encodeURIComponent(sessionId)}/backfill-status`
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
      `http://localhost:8080/api/v1/sessions/${encodeURIComponent(sessionId)}/dashboard`
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
      `http://localhost:8080/api/v1/sessions/${encodeURIComponent(sessionId)}/transactions/rebuild`
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
          `http://localhost:8080/api/v1/sessions/${encodeURIComponent(sessionId)}/transactions` &&
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
});
