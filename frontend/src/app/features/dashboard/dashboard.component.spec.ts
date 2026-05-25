import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { Component } from '@angular/core';
import { of, throwError } from 'rxjs';

import { EMPTY_DASHBOARD_DATA } from '../../core/data/dashboard.constants';
import { DashboardDataService } from '../../core/services/dashboard-data.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SessionStorageService } from '../../core/services/session-storage.service';
import { SessionBackfillStatusResponse, SessionRefreshResponse, SessionTransactionsResponse } from '../../core/models/wallet-api.models';
import { DashboardComponent } from './dashboard.component';

@Component({ standalone: true, template: '' })
class DummySettingsComponent {}

describe('DashboardComponent (wallet submit flow)', () => {
  const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
  const dashboardData = {
    ...EMPTY_DASHBOARD_DATA,
    wallets: [
      {
        id: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
        label: 'Wallet 1',
        address: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
        color: '#22d3ee',
      },
    ],
    networks: [
      {
        id: 'ETHEREUM',
        icon: '⟠',
        label: 'Ethereum',
        color: '#627EEA',
      },
    ],
  };
  const runningBackfill: SessionBackfillStatusResponse = {
    sessionId,
    status: 'RUNNING',
    acquisitionStatus: 'RUNNING',
    overallProgressPct: 20,
    totalTargets: 8,
    completedTargets: 1,
    wallets: [
      {
        address: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
        label: 'Wallet 1',
        color: '#22d3ee',
        networks: [
          {
            networkId: 'ETHEREUM',
            status: 'RUNNING',
            progressPct: 20,
            lastBlockSynced: 123,
            backfillComplete: false,
            syncBannerMessage: 'Backfill queued',
          },
        ],
      },
    ],
  };
  const completeBackfill: SessionBackfillStatusResponse = {
    ...runningBackfill,
    status: 'COMPLETE',
    acquisitionStatus: 'COMPLETE',
    overallProgressPct: 100,
    completedTargets: 8,
    totalTargets: 8,
    wallets: [
      {
        ...runningBackfill.wallets[0],
        networks: [
          {
            networkId: 'ETHEREUM',
            status: 'COMPLETE',
            progressPct: 100,
            lastBlockSynced: 456,
            backfillComplete: true,
            syncBannerMessage: null,
          },
        ],
      },
    ],
  };
  const sessionTransactionsResponse: SessionTransactionsResponse = {
    sessionId,
    offset: 0,
    limit: 50,
    totalCount: 1,
    hasMore: false,
    items: [
      {
        id: 'stx-1',
        sourceType: 'CHAIN',
        txHash: '0xbridge',
        networkId: 'ETHEREUM',
        walletAddress: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
        matchedCounterparty: 'BYBIT:33625378',
        blockTimestamp: '2026-03-06T10:00:00Z',
        type: 'EXTERNAL_TRANSFER_OUT',
        status: 'PENDING_PRICE',
        issue: 'missing_price',
        bridgeStatus: 'MATCHED',
        realisedPnlUsdTotal: null,
        avcoSnapshotVersion: null,
        flows: [
          {
            role: 'TRANSFER',
            assetContract: '0xasset',
            assetSymbol: 'USDC',
            quantityDelta: -100,
            unitPriceUsd: 1,
            valueUsd: -100,
            priceSource: 'STABLECOIN',
            logIndex: 0,
          },
        ],
      },
    ],
  };

  let walletApiServiceSpy: jasmine.SpyObj<WalletApiService>;
  let sessionStorageServiceSpy: jasmine.SpyObj<SessionStorageService>;

  beforeEach(async () => {
    walletApiServiceSpy = jasmine.createSpyObj<WalletApiService>('WalletApiService', [
      'addSession',
      'getSession',
      'getSessionSettings',
      'getSessionBackfillStatus',
      'refreshSession',
      'rebuildSessionTransactions',
      'getSessionTransactions',
    ]);
    sessionStorageServiceSpy = jasmine.createSpyObj<SessionStorageService>('SessionStorageService', [
      'getSessionId',
      'setSessionId',
      'clearSessionId',
    ]);
    sessionStorageServiceSpy.getSessionId.and.returnValue(null);
    walletApiServiceSpy.addSession.and.returnValue(
      of({
        sessionId,
        message: 'Session saved, universe sync scheduled',
      })
    );
    walletApiServiceSpy.getSessionSettings.and.returnValue(
      of({
        sessionId,
        wallets: [],
        integrations: [],
        hideSmallAssets: true,
        showReconciliationWarnings: true,
      })
    );
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(runningBackfill));
    walletApiServiceSpy.refreshSession.and.returnValue(
      of({
        sessionId,
        status: 'SCHEDULED',
        scheduledTargets: 1,
        skippedTargets: 0,
        message: 'Incremental refresh queued',
      } satisfies SessionRefreshResponse)
    );
    walletApiServiceSpy.rebuildSessionTransactions.and.returnValue(
      of({
        sessionId,
        projectedTransactions: 1,
        message: 'Session transactions rebuilt',
      })
    );
    walletApiServiceSpy.getSessionTransactions.and.returnValue(of(sessionTransactionsResponse));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([{ path: 'settings', component: DummySettingsComponent }]),
        {
          provide: DashboardDataService,
          useValue: {
            getDashboardData: () => of(dashboardData),
          },
        },
        {
          provide: WalletApiService,
          useValue: walletApiServiceSpy,
        },
        {
          provide: SessionStorageService,
          useValue: sessionStorageServiceSpy,
        },
      ],
    }).compileComponents();
  });

  it('enables Add Wallet(s) submit when at least one wallet address is valid', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    fixture.detectChanges();

    expect(component.canSubmitWallets()).toBeTrue();
  });

  it('shows backfill progress only after successful POST /sessions', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.isBackfillVisible()).toBeFalse();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    component.submitWallets();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.addSession).toHaveBeenCalled();
    expect(component.isBackfillVisible()).toBeTrue();
    expect(sessionStorageServiceSpy.setSessionId).toHaveBeenCalledWith(sessionId);

    tick(1);
    fixture.detectChanges();

    expect(walletApiServiceSpy.getSessionBackfillStatus).toHaveBeenCalledWith(sessionId);
    expect(component.backfillProgressPct()).toBe(20);
  }));

  it('reuses sessionId from storage for subsequent POST /sessions', () => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    component.submitWallets();
    fixture.detectChanges();

    expect(walletApiServiceSpy.addSession).toHaveBeenCalled();
    const submitPayload = walletApiServiceSpy.addSession.calls.mostRecent().args[0];
    expect(submitPayload.sessionId).toBe(sessionId);
  });

  it('restores backfill polling from sessionId in storage', fakeAsync(() => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.getSessionBackfillStatus).toHaveBeenCalledWith(sessionId);
    expect(component.isBackfillVisible()).toBeTrue();
  }));

  it('clears session storage when restored session is missing (404)', fakeAsync(() => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 }))
    );
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(sessionStorageServiceSpy.clearSessionId).toHaveBeenCalled();
    expect(component.isBackfillVisible()).toBeFalse();
  }));

  it('rebuilds and loads session transactions when backfill becomes terminal', fakeAsync(() => {
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(completeBackfill));
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    component.submitWallets();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.getSessionTransactions).toHaveBeenCalledWith(sessionId, {
      limit: 50,
      offset: 0,
      search: '',
      bridgeStatus: 'ALL',
      spamFilter: 'HIDE_SPAM',
      walletIds: undefined,
      networkIds: undefined,
    });
    expect(component.transactionPaneTransactions()[0].hash).toBe('0xbridge');
    expect(component.transactionPaneTransactions()[0].bridgeStatus).toBe('MATCHED');
  }));

  it('preserves backend price sources like BYBIT without degrading them to UNKNOWN', fakeAsync(() => {
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(completeBackfill));
    walletApiServiceSpy.getSessionTransactions.and.returnValue(
      of({
        ...sessionTransactionsResponse,
        items: [
          {
            ...sessionTransactionsResponse.items[0],
            flows: [
              {
                ...sessionTransactionsResponse.items[0].flows[0],
                role: 'FEE',
                assetSymbol: 'ETH',
                quantityDelta: -0.0001,
                unitPriceUsd: 2153.2,
                valueUsd: -0.21532,
                priceSource: 'BYBIT',
              },
            ],
          },
        ],
      })
    );

    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    component.submitWallets();
    tick();
    fixture.detectChanges();

    expect(component.transactionPaneTransactions()[0].flows[0].source).toBe('BYBIT');
  }));

  it('renders explicit pipeline stage labels for classification, clarification, linking and cost basis', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 42,
      pipelineStage: 'ON_CHAIN_NORMALIZATION',
    });
    expect(component.pipelineStatusLabel()).toBe('Classification: 42% done');

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 58,
      phaseProgress: {
        phase: 'ON_CHAIN_CLARIFICATION',
        progressPct: 58,
        processedCount: 58,
        leftCount: 42,
        totalCount: 100,
      },
    });
    expect(component.pipelineStatusLabel()).toBe('Clarification: 58% done');

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 73,
      pipelineStage: 'ON_CHAIN_RECLASSIFICATION',
      phaseProgress: null,
    });
    expect(component.pipelineStatusLabel()).toBe('Reclassification: 73% done');

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 78,
      pipelineStage: 'LINKING',
      phaseProgress: null,
    });
    expect(component.pipelineStatusLabel()).toBe('Linking: 78% done');

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 91,
      pipelineStage: 'ACCOUNTING_REPLAY',
      phaseProgress: null,
    });
    expect(component.pipelineStatusLabel()).toBe('Cost basis: 91% done');

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 96,
      pipelineStage: 'PORTFOLIO_SNAPSHOT_REFRESH',
      phaseProgress: null,
    });
    expect(component.pipelineStatusLabel()).toBe('Portfolio snapshot: 96% done');
  });

  it('renders pricing subline as priced transaction progress', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.sessionBackfillStatus.set({
      ...runningBackfill,
      overallProgressPct: 64,
      phaseProgress: {
        phase: 'PRICING',
        progressPct: 64,
        processedCount: 2793,
        leftCount: 2826,
        totalCount: 5619,
      },
    });

    expect(component.pipelineStatusLabel()).toBe('Pricing: 64% done');
    expect(component.pipelineStatusSubline()).toBe('priced tx: 2793 · left: 2826');
  });

  it('maps sponsored gas transactions into GAS_ONLY display type', fakeAsync(() => {
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(completeBackfill));
    walletApiServiceSpy.getSessionTransactions.and.returnValue(
      of({
        ...sessionTransactionsResponse,
        items: [
          {
            ...sessionTransactionsResponse.items[0],
            type: 'SPONSORED_GAS_IN',
            flows: [
              {
                ...sessionTransactionsResponse.items[0].flows[0],
                role: 'BUY',
                assetSymbol: 'ETH',
                quantityDelta: 0.00021,
                unitPriceUsd: 2200,
                valueUsd: 0.462,
              },
            ],
          },
        ],
      })
    );

    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.addWalletsForm.controls.wallets.controls[0].controls.address.setValue(
      '0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f'
    );
    component.submitWallets();
    tick();
    fixture.detectChanges();

    expect(component.transactionPaneTransactions()[0].type).toBe('GAS_ONLY');
  }));

  it('treats wallet and integration filters as checked by default', fakeAsync(() => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(completeBackfill));
    walletApiServiceSpy.getSessionSettings.and.returnValue(
      of({
        sessionId,
        wallets: [],
        integrations: [
          {
            integrationId: 'BYBIT-33625378',
            provider: 'BYBIT',
            status: 'READY',
            displayName: 'Bybit',
            accountRef: 'BYBIT:33625378',
            maskedKey: '****abcd',
            readOnly: true,
            capabilities: ['READ'],
            lastValidatedAt: null,
            lastSyncAt: null,
            lastError: null,
            totalSegments: 10,
            completedSegments: 10,
            failedSegments: 0,
            progressPct: 100,
            streamSync: [],
          },
        ],
        hideSmallAssets: true,
        showReconciliationWarnings: true,
      })
    );

    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(component.isWalletSelected('0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f')).toBeTrue();
    expect(component.isNetworkSelected('ETHEREUM')).toBeTrue();
    expect(component.isIntegrationSelected('BYBIT:33625378')).toBeTrue();

    component.toggleIntegration('BYBIT:33625378');
    tick();
    fixture.detectChanges();

    expect(component.isIntegrationSelected('BYBIT:33625378')).toBeFalse();
  }));

  it('schedules incremental refresh when the session is already complete', fakeAsync(() => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(completeBackfill));

    const fixture = TestBed.createComponent(DashboardComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(component.canRefreshSession()).toBeTrue();

    component.onRefreshSession();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.refreshSession).toHaveBeenCalledWith(sessionId);
    expect(component.isSessionRefreshSubmitting()).toBeFalse();
    expect(component.sessionRefreshMessage()).toBe('Incremental refresh queued');
  }));
});
