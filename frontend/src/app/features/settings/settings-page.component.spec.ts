import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import {
  AddSessionRequest,
  PutSessionSettingsRequest,
  SessionRefreshResponse,
  SessionSettingsResponse,
} from '../../core/models/wallet-api.models';
import { SessionStorageService } from '../../core/services/session-storage.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SettingsPageComponent } from './settings-page.component';

describe('SettingsPageComponent', () => {
  const sessionId = '549b0aba-a9af-4789-b125-ebb86314a3f1';
  const settingsResponse: SessionSettingsResponse = {
    sessionId,
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
        integrationId: 'BYBIT-33625378',
        provider: 'BYBIT',
        status: 'READY',
        displayName: 'Bybit main',
        accountRef: 'BYBIT:33625378',
        color: '#f7a600',
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

  let walletApiServiceSpy: jasmine.SpyObj<WalletApiService>;
  let sessionStorageServiceSpy: jasmine.SpyObj<SessionStorageService>;

  beforeEach(async () => {
    walletApiServiceSpy = jasmine.createSpyObj<WalletApiService>('WalletApiService', [
      'addSession',
      'getSessionSettings',
      'putSessionSettings',
      'refreshSession',
      'testIntegrationConnection',
    ]);
    sessionStorageServiceSpy = jasmine.createSpyObj<SessionStorageService>('SessionStorageService', [
      'getSessionId',
      'setSessionId',
    ]);
    sessionStorageServiceSpy.getSessionId.and.returnValue(sessionId);
    walletApiServiceSpy.addSession.and.callFake((payload: AddSessionRequest) =>
      of({
        sessionId: payload.sessionId,
        message: 'Session created',
      })
    );
    walletApiServiceSpy.getSessionSettings.and.returnValue(of(settingsResponse));
    walletApiServiceSpy.refreshSession.and.returnValue(
      of({
        sessionId,
        status: 'UP_TO_DATE',
        scheduledTargets: 0,
        skippedTargets: 0,
        message: 'ok',
      } satisfies SessionRefreshResponse)
    );
    walletApiServiceSpy.putSessionSettings.and.callFake((_, payload: PutSessionSettingsRequest) =>
      of({
        ...settingsResponse,
        wallets: payload.wallets,
        hideSmallAssets: payload.hideSmallAssets,
        showReconciliationWarnings: payload.showReconciliationWarnings,
        integrations: payload.integrations.length === 0
          ? []
          : settingsResponse.integrations.map((integration) => ({
              ...integration,
              displayName: payload.integrations[0]?.displayName ?? integration.displayName,
            })),
      })
    );

    await TestBed.configureTestingModule({
      imports: [SettingsPageComponent],
      providers: [
        provideRouter([]),
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

  it('loads current session settings on init', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.getSessionSettings).toHaveBeenCalledWith(sessionId);
    expect(component.bybitIntegration()?.accountRef).toBe('BYBIT:33625378');
    expect(component.walletCount()).toBe(1);
    expect(component.hasSession()).toBeTrue();
    expect(component.walletsDraft()[0]?.networks).toEqual(component.allNetworkIds);
    expect(component.pendingWallets()).toEqual([]);
  }));

  it('saves general settings through full session overwrite payload', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.generalForm.controls.hideSmallAssets.setValue(false);
    component.generalForm.controls.showReconciliationWarnings.setValue(false);
    component.saveGeneral();
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(sessionId, jasmine.objectContaining({
      wallets: [
        jasmine.objectContaining({
          address: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
          label: 'Main',
          color: '#22d3ee',
          networks: jasmine.arrayWithExactContents([...component.allNetworkIds]),
        }),
      ],
      hideSmallAssets: false,
      showReconciliationWarnings: false,
      integrations: [
        jasmine.objectContaining({
          provider: 'BYBIT',
          displayName: 'Bybit main',
          apiKey: '',
          apiSecret: '',
        }),
      ],
    }));
  }));

  it('connects or updates Bybit through the same overwrite endpoint', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.integrationForm.controls.displayName.setValue('Bybit treasury');
    component.integrationForm.controls.apiKey.setValue('api-key');
    component.integrationForm.controls.apiSecret.setValue('api-secret');
    component.saveIntegration('BYBIT');
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(sessionId, jasmine.objectContaining({
      integrations: [
        jasmine.objectContaining({
          provider: 'BYBIT',
          displayName: 'Bybit treasury',
          apiKey: 'api-key',
          apiSecret: 'api-secret',
        }),
      ],
    }));
    expect(component.integrationForm.controls.apiKey.value).toBe('abcd...1234');
    expect(component.integrationForm.controls.apiSecret.value).toBe('');
  }));

  it('keeps separate pending wallet rows so several wallets can be queued before one save', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.startAddWallet();
    const firstNewWallet = component.pendingWallets()[0]!;
    component.updatePendingWalletField(firstNewWallet.id, 'address', '0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f');
    component.updatePendingWalletField(firstNewWallet.id, 'label', 'Second');
    component.startAddWallet();
    fixture.detectChanges();

    expect(component.walletCount()).toBe(3);
    expect(component.pendingWallets()[0]?.address).toBe('0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f');
    expect(component.pendingWallets()[0]?.label).toBe('Second');
    expect(component.pendingWallets()[1]?.label).toBe('Wallet 3');
    expect(component.pendingWallets()[1]?.networks).toEqual(component.allNetworkIds);
  }));

  it('saves all inline wallet drafts in one request', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.startAddWallet();
    const secondWallet = component.pendingWallets()[0]!;
    component.updatePendingWalletField(secondWallet.id, 'label', 'TWT');
    component.updatePendingWalletField(secondWallet.id, 'address', '0xf03b52e8686b962e051a6075a06b96cb8a663021');

    component.startAddWallet();
    const thirdWallet = component.pendingWallets()[1]!;
    component.updatePendingWalletField(thirdWallet.id, 'label', 'Uni');
    component.updatePendingWalletField(thirdWallet.id, 'address', '0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f');

    component.saveWallets();
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(sessionId, jasmine.objectContaining({
      wallets: [
        jasmine.objectContaining({ label: 'Main' }),
        jasmine.objectContaining({ label: 'TWT', address: '0xf03b52e8686b962e051a6075a06b96cb8a663021' }),
        jasmine.objectContaining({ label: 'Uni', address: '0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f' }),
      ],
    }));
  }));

  it('includes new Bybit credentials in confirm data sources save payload', fakeAsync(() => {
    const noBybitSettings: SessionSettingsResponse = {
      ...settingsResponse,
      integrations: [],
    };
    walletApiServiceSpy.getSessionSettings.and.returnValue(of(noBybitSettings));

    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(component.bybitIntegration()).toBeNull();

    component.integrationForm.controls.apiKey.setValue('new-key');
    component.integrationForm.controls.apiSecret.setValue('new-secret');
    expect(component.dataSourcesChangesCount()).toBeGreaterThan(0);
    component.openDataSourcesReview();
    component.confirmDataSourcesSave();
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(
      sessionId,
      jasmine.objectContaining({
        integrations: [
          jasmine.objectContaining({
            provider: 'BYBIT',
            apiKey: 'new-key',
            apiSecret: 'new-secret',
          }),
        ],
      })
    );
  }));

  it('save wallets includes Bybit when key and secret are filled before first connect', fakeAsync(() => {
    const noBybitSettings: SessionSettingsResponse = {
      ...settingsResponse,
      integrations: [],
    };
    walletApiServiceSpy.getSessionSettings.and.returnValue(of(noBybitSettings));

    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.startAddWallet();
    const w = component.pendingWallets()[0]!;
    component.updatePendingWalletField(w.id, 'address', '0xf03b52e8686b962e051a6075a06b96cb8a663021');
    component.updatePendingWalletField(w.id, 'label', 'Second');
    component.integrationForm.controls.apiKey.setValue('k');
    component.integrationForm.controls.apiSecret.setValue('s');
    component.saveWallets();
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(
      sessionId,
      jasmine.objectContaining({
        integrations: [
          jasmine.objectContaining({ provider: 'BYBIT', apiKey: 'k', apiSecret: 's' }),
        ],
      })
    );
  }));

  it('tests integration connection without persisting credentials', fakeAsync(() => {
    walletApiServiceSpy.testIntegrationConnection.and.returnValue(
      of({
        provider: 'DZENGI',
        accountRef: 'DZENGI:12345678',
        maskedKey: 'abcd...1234',
        readOnly: true,
        message: 'Dzengi credentials validated',
      })
    );

    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.integrationForm.controls.apiKey.setValue('test-key');
    component.integrationForm.controls.apiSecret.setValue('test-secret');
    component.testIntegrationConnection('DZENGI');
    tick();

    expect(walletApiServiceSpy.testIntegrationConnection).toHaveBeenCalledWith(sessionId, {
      provider: 'DZENGI',
      apiKey: 'test-key',
      apiSecret: 'test-secret',
    });
    expect(component.testConnectionMessage()).toBe('Dzengi credentials validated');
  }));

  it('connects Dzengi through the same overwrite endpoint', fakeAsync(() => {
    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.onIntegrationProviderSelected('DZENGI');
    component.integrationForm.controls.displayName.setValue('Dzengi main');
    component.integrationForm.controls.apiKey.setValue('dz-key');
    component.integrationForm.controls.apiSecret.setValue('dz-secret');
    component.saveIntegration('DZENGI');
    tick();

    expect(walletApiServiceSpy.putSessionSettings).toHaveBeenCalledWith(sessionId, jasmine.objectContaining({
      integrations: jasmine.arrayContaining([
        jasmine.objectContaining({
          provider: 'BYBIT',
          displayName: 'Bybit main',
          apiKey: '',
          apiSecret: '',
        }),
        jasmine.objectContaining({
          provider: 'DZENGI',
          displayName: 'Dzengi main',
          apiKey: 'dz-key',
          apiSecret: 'dz-secret',
        }),
      ]),
    }));
  }));

  it('creates an empty session from sign in button when no session is active', fakeAsync(() => {
    sessionStorageServiceSpy.getSessionId.and.returnValue(null);

    const fixture = TestBed.createComponent(SettingsPageComponent);
    const component = fixture.componentInstance;

    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    component.createEmptySession();
    tick();
    fixture.detectChanges();

    expect(walletApiServiceSpy.addSession).toHaveBeenCalledWith(jasmine.objectContaining({
      wallets: [],
    }));
    expect(sessionStorageServiceSpy.setSessionId).toHaveBeenCalled();
    expect(walletApiServiceSpy.getSessionSettings).toHaveBeenCalled();
    expect(component.hasSession()).toBeTrue();
  }));
});
