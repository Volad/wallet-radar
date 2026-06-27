import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { LpDataService } from './lp-data.service';
import { SessionLpResponse } from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

describe('LpDataService', () => {
  let service: LpDataService;
  let walletApiServiceSpy: jasmine.SpyObj<WalletApiService>;

  const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';

  const mockResponse: SessionLpResponse = {
    sessionId,
    summary: {
      activeTvlUsd: 4991,
      feesEarnedUsd: 223.6,
      unclaimedUsd: 41.2,
      inRange: 1,
      outOfRange: 1,
      realizedPnlUsd: -170,
    },
    positions: [
      {
        correlationId: 'uni-eth-usdc',
        protocol: 'Uniswap V3',
        family: 'CL_NFT',
        networkId: 'ETHEREUM',
        wallet: '0x1234567890abcdef1234567890abcdef12345678',
        pair: 'ETH / USDC',
        token0: { symbol: 'ETH', quantity: 0.812, valueUsd: 2581 },
        token1: { symbol: 'USDC', quantity: 2410, valueUsd: 2410 },
        feeTierPct: 0.05,
        tokenId: '#841022',
        status: 'in_range',
        staked: false,
        range: {
          priceLow: 2800,
          priceHigh: 3600,
          priceCurrent: 3180,
          priceUnit: 'ETH/USDC',
        },
        tvlUsd: { valueUsd: 4991, precision: 'EXACT' },
        costBasisUsd: 4068,
        withdrawnUsd: 0,
        fees: {
          claimedUsd: 182.4,
          unclaimedUsd: 41.2,
          precision: 'ESTIMATED',
          perToken: [
            { symbol: 'ETH', claimed: 0.02, unclaimed: 0.004 },
            { symbol: 'USDC', claimed: 120, unclaimed: 20 },
          ],
        },
        il: { pct: -2.8, usd: -141, precision: 'ESTIMATED' },
        priceAppreciationUsd: 41,
        netPnlUsd: 82.4,
        accountingUnrealizedUsd: 82.4,
        apr: { now: 12.8, avg: 14.4, precision: 'ESTIMATED' },
        earningsDaily: [{ date: '2026-06-01', value: 1.2 }],
        aprDaily: [{ date: '2026-06-01', value: 12.5 }],
        txns: [
          {
            id: 'tx-1',
            type: 'LP_ENTRY',
            label: 'Add liquidity',
            assetSymbol: 'ETH',
            quantity: -1.2,
            valueUsd: 3360,
            assetSymbol1: 'USDC',
            quantity1: -3360,
            valueUsd1: 3360,
            totalValueUsd: 6720,
            gasFeeUsd: 0.01,
            valueUsdPrecision: 'EXACT',
            txHash: '0xabc',
            blockTimestamp: '2024-10-12T00:00:00Z',
          },
        ],
        enteredAt: '2024-10-12T00:00:00Z',
        closedAt: null,
        snapshotAt: '2026-06-24T10:00:00Z',
        snapshotStale: false,
        trackingStartedAt: '2024-10-12T00:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    walletApiServiceSpy = jasmine.createSpyObj<WalletApiService>('WalletApiService', ['getSessionLp']);
    walletApiServiceSpy.getSessionLp.and.returnValue(of(mockResponse));

    TestBed.configureTestingModule({
      providers: [
        LpDataService,
        { provide: WalletApiService, useValue: walletApiServiceSpy },
      ],
    });

    service = TestBed.inject(LpDataService);
  });

  it('maps session LP response into UI model', (done) => {
    service.getSessionLp(sessionId).subscribe((data) => {
      expect(data.sessionId).toBe(sessionId);
      expect(data.summary.activeTvlUsd).toBe(4991);
      expect(data.summary.inRange).toBe(1);
      expect(data.positions.length).toBe(1);

      const position = data.positions[0];
      expect(position.correlationId).toBe('uni-eth-usdc');
      expect(position.wallet).toBe('0x1234567890abcdef1234567890abcdef12345678');
      expect(position.status).toBe('in_range');
      expect(position.tvlUsd.precision).toBe('EXACT');
      expect(position.fees.perToken.length).toBe(2);
      expect(position.earningsDaily[0].value).toBe(1.2);
      expect(position.txns[0].type).toBe('LP_ENTRY');
      expect(position.txns[0].legs.length).toBe(2);
      expect(position.txns[0].totalValueUsd).toBe(6720);
      done();
    });
  });

  it('maps backend API shape with flat tvlUsd and perToken object', () => {
    const mapped = service.mapPosition({
      correlationId: 'lp-position:base:0xabc:1',
      protocol: 'Uniswap',
      family: 'CL_NFT',
      networkId: 'BASE',
      walletAddress: '0xABCDEF1234567890ABCDEF1234567890ABCDEF12',
      pair: 'USDC/ETH',
      token0: { sym: 'USDC', qty: 100, usd: 100 },
      token1: { sym: 'ETH', qty: 0.5, usd: 1500 },
      feeTierPct: null,
      tokenId: '1',
      status: 'out_of_range',
      staked: false,
      range: null,
      tvlUsd: 1600,
      tvlPrecision: 'ESTIMATE',
      costBasisUsd: 1500,
      withdrawnUsd: 0,
      fees: {
        claimedUsd: 10,
        unclaimedUsd: 5,
        claimedPrecision: 'EXACT',
        perToken: { USDC: 3, ETH: 2 },
      },
      il: { pct: -1, usd: -10, precision: 'ESTIMATE' },
      priceAppreciationUsd: 100,
      netPnlUsd: 105,
      accountingUnrealizedUsd: 100,
      apr: { nowPct: 12, avgPct: 10, precision: 'ESTIMATE' },
      earningsDaily: [{ day: '2026-06-24', earnedUsd: 1.5, precision: 'ESTIMATE' }],
      aprDaily: [{ day: '2026-06-24', aprPct: 11, precision: 'ESTIMATE' }],
      txns: [{
        id: 'tx-1',
        type: 'LP_ENTRY',
        txHash: '0xhash',
        timestamp: '2024-10-12T00:00:00Z',
        assetSymbol: 'ETH',
        quantity: -1,
        valueUsd: -3000,
      }],
      enteredAt: '2024-10-12T00:00:00Z',
      closedAt: null,
      snapshotAt: '2026-06-24T10:00:00Z',
      snapshotStale: false,
      trackingStartedAt: null,
    });

    expect(mapped.wallet).toBe('0xabcdef1234567890abcdef1234567890abcdef12');
    expect(mapped.token0.symbol).toBe('USDC');
    expect(mapped.tvlUsd.valueUsd).toBe(1600);
    expect(mapped.tvlUsd.precision).toBe('ESTIMATED');
    expect(mapped.apr.now).toBe(12);
    expect(mapped.fees.perToken.length).toBe(2);
    expect(mapped.earningsDaily[0].date).toBe('2026-06-24');
    expect(mapped.txns[0].legs[0].symbol).toBe('ETH');
  });

  it('maps unavailable precision without coercing to zero USD', () => {
    const mapped = service.mapPosition({
      ...mockResponse.positions[0],
      tvlUsd: { valueUsd: null, precision: 'UNAVAILABLE' },
      fees: {
        claimedUsd: null,
        unclaimedUsd: null,
        precision: 'UNAVAILABLE',
        perToken: [],
      },
    });

    expect(mapped.tvlUsd.valueUsd).toBeNull();
    expect(mapped.tvlUsd.precision).toBe('UNAVAILABLE');
    expect(mapped.fees.claimedUsd).toBeNull();
  });
});
