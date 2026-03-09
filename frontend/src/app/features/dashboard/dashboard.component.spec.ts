import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { DASHBOARD_MOCK_DATA } from '../../core/data/dashboard.mock';
import { DashboardDataService } from '../../core/services/dashboard-data.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SessionStorageService } from '../../core/services/session-storage.service';
import { SessionBackfillStatusResponse, SessionTransactionsResponse } from '../../core/models/wallet-api.models';
import { DashboardComponent } from './dashboard.component';

describe('DashboardComponent (wallet submit flow)', () => {
  const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
  const runningBackfill: SessionBackfillStatusResponse = {
    sessionId,
    status: 'RUNNING',
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
    items: [
      {
        id: 'stx-1',
        sourceType: 'CHAIN',
        txHash: '0xbridge',
        networkId: 'ETHEREUM',
        walletAddress: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
        blockTimestamp: '2026-03-06T10:00:00Z',
        type: 'EXTERNAL_TRANSFER_OUT',
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
      'getSessionBackfillStatus',
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
        message: 'Session saved, backfill started',
      })
    );
    walletApiServiceSpy.getSessionBackfillStatus.and.returnValue(of(runningBackfill));
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
        {
          provide: DashboardDataService,
          useValue: {
            getDashboardData: () => of(DASHBOARD_MOCK_DATA),
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

    expect(walletApiServiceSpy.rebuildSessionTransactions).toHaveBeenCalledWith(sessionId);
    expect(walletApiServiceSpy.getSessionTransactions).toHaveBeenCalledWith(sessionId, 100);
    expect(component.transactionPaneTransactions()[0].hash).toBe('0xbridge');
    expect(component.transactionPaneTransactions()[0].bridgeStatus).toBe('MATCHED');
  }));
});
