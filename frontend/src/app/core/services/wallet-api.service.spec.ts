import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  AddSessionRequest,
  SessionBackfillStatusResponse,
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
});
