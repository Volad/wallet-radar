import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, of, switchMap, tap } from 'rxjs';

import { COLORS, EVM_NETWORKS_PRESENTATION } from '../../core/data/dashboard.constants';
import {
  EvmNetworkId,
  OnChainWalletNetworkId,
  PutSessionSettingsRequest,
  SessionExternalVenueEntry,
  SessionIntegrationResponse,
  SessionSettingsResponse,
} from '../../core/models/wallet-api.models';
import {
  detectWalletDomain,
  isValidWalletAddress,
  normalizeWalletAddress,
  WalletAddressDomain,
} from '../../core/utils/wallet-address.util';
import { SessionStorageService } from '../../core/services/session-storage.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { formatDateTimeWithSeconds } from '../../core/utils/date-time.util';
import { SettingsWizardComponent } from './wizard/settings-wizard.component';
import { AccountSettingsSectionComponent } from './sections/account-settings-section.component';
import { AccountingSettingsSectionComponent } from './sections/accounting-settings-section.component';
import { GeneralSettingsSectionComponent } from './sections/general-settings-section.component';
import { IntegrationsSettingsSectionComponent, AVAILABLE_PROVIDERS } from './sections/integrations-settings-section.component';
import { WalletsSettingsSectionComponent } from './sections/wallets-settings-section.component';

type SettingsSectionId = 'wallets' | 'integrations' | 'accounting' | 'general';
type SettingsSaveScope = SettingsSectionId;

const INTEGRATION_COLOR_PALETTE = [
  '#f7a600', '#22d3ee', '#60a5fa', '#34d399',
  '#a78bfa', '#f472b6', '#fb923c', '#e2e8f0',
];

function pickUnusedColor(palette: ReadonlyArray<string>, usedColors: ReadonlyArray<string | null>): string {
  const used = new Set(usedColors.filter(Boolean).map((c) => c!.toLowerCase()));
  const available = palette.filter((c) => !used.has(c.toLowerCase()));
  const pool = available.length > 0 ? available : palette;
  return pool[Math.floor(Math.random() * pool.length)]!;
}

function pickUnusedIntegrationColor(usedColors: ReadonlyArray<string | null>): string {
  return pickUnusedColor(INTEGRATION_COLOR_PALETTE, usedColors);
}
type StatusTone = 'ready' | 'busy' | 'error' | 'idle';

interface DataSourcesChangeItem {
  readonly label: string;
  readonly tag?: string;
  readonly tagTone?: 'green' | 'amber' | 'red' | 'cyan';
}

type SettingsSidebarNavIcon = 'monitor' | 'gear';

interface SettingsSectionMeta {
  readonly id: SettingsSectionId;
  readonly label: string;
  readonly navIcon: SettingsSidebarNavIcon;
}

interface SettingsWalletDraft {
  readonly id: string;
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<OnChainWalletNetworkId>;
  readonly networksOpen: boolean;
  readonly domain: WalletAddressDomain;
}

const SETTINGS_SECTIONS: ReadonlyArray<SettingsSectionMeta> = [
  { id: 'wallets', label: 'Data sources', navIcon: 'monitor' },
  { id: 'general', label: 'General', navIcon: 'gear' },
];

const WALLET_COLOR_PALETTE: ReadonlyArray<string> = [
  COLORS.cyan,
  COLORS.purple,
  COLORS.green,
  COLORS.amber,
  '#60a5fa',
  '#f472b6',
  '#34d399',
];


@Component({
  selector: 'wr-settings-page',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    SettingsWizardComponent,
    AccountSettingsSectionComponent,
    AccountingSettingsSectionComponent,
    GeneralSettingsSectionComponent,
    IntegrationsSettingsSectionComponent,
    WalletsSettingsSectionComponent,
  ],
  templateUrl: './settings-page.component.html',
  styleUrl: './settings-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
})
export class SettingsPageComponent {
  readonly maxWallets = 10;
  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly sections = SETTINGS_SECTIONS;
  readonly networksPresentation = EVM_NETWORKS_PRESENTATION;
  readonly allNetworkIds: ReadonlyArray<EvmNetworkId> = this.networksPresentation.map((network) => network.id);
  /** On-chain-only network IDs (all EvmNetworkId values are on-chain since BYBIT was removed from the union). */
  private readonly onChainNetworkIds: ReadonlyArray<OnChainWalletNetworkId> = this.allNetworkIds as ReadonlyArray<OnChainWalletNetworkId>;

  readonly sessionId = signal<string | null>(this.sessionStorageService.getSessionId());
  readonly settings = signal<SessionSettingsResponse | null>(null);
  readonly walletsDraft = signal<ReadonlyArray<SettingsWalletDraft>>([]);
  readonly pendingWallets = signal<ReadonlyArray<SettingsWalletDraft>>([]);
  /**
   * Cycle/9 S2: external venues (Paradex/MEX/etc.) round-tripped on save so editing other
   * settings doesn't wipe the registry. Dedicated UI block lands in a follow-up; for now
   * venues can be managed via direct PUT /sessions/{id}/settings.
   */
  readonly externalVenues = signal<ReadonlyArray<SessionExternalVenueEntry>>([]);
  readonly activeSection = signal<SettingsSectionId>('wallets');
  readonly loading = signal(false);
  readonly savingScope = signal<SettingsSaveScope | null>(null);
  readonly error = signal<string | null>(null);
  readonly saveMessage = signal<string | null>(null);
  readonly dataSourcesReviewOpen = signal(false);
  readonly dataSourcesSaving = signal(false);
  readonly showIntegrationSecret = signal(false);
  readonly testingConnection = signal(false);
  readonly testConnectionMessage = signal<string | null>(null);
  readonly signingIn = signal(false);
  readonly walletListDirty = signal(false);
  readonly activeIntegrationProvider = signal<string>('BYBIT');

  readonly generalForm = this.formBuilder.nonNullable.group({
    hideSmallAssets: [true],
    showReconciliationWarnings: [true],
  });

  readonly integrationForm = this.formBuilder.nonNullable.group({
    displayName: ['Bybit'],
    apiKey: [''],
    apiSecret: [''],
  });

  private readonly integrationDraft = signal<{ displayName: string; apiKey: string; apiSecret: string }>({
    displayName: 'Bybit',
    apiKey: '',
    apiSecret: '',
  });

  private readonly integrationInitialSnapshot = signal<{ provider: string; apiKey: string }>({
    provider: 'BYBIT',
    apiKey: '',
  });

  readonly hasSession = computed(() => this.sessionId() !== null);
  readonly bybitIntegration = computed(
    () => this.settings()?.integrations.find((integration) => integration.provider === 'BYBIT') ?? null
  );
  /** All connected integrations for display */
  readonly allIntegrations = computed(() => this.settings()?.integrations ?? []);
  readonly formatDateTimeWithSeconds = formatDateTimeWithSeconds;
  readonly walletCount = computed(() => this.walletsDraft().length + this.pendingWallets().length);
  readonly hasWalletsDraft = computed(() => this.walletCount() > 0);
  readonly hasSavedWallets = computed(() => this.walletsDraft().length > 0);
  readonly isWalletEditing = computed(() => this.pendingWallets().length > 0);
  readonly canAddMoreWallets = computed(() => this.walletCount() < this.maxWallets);
  readonly isSaving = computed(() => this.savingScope() !== null);
  readonly canSaveWallets = computed(
    () =>
      !this.isSaving() &&
      (
        this.walletListDirty() ||
        this.pendingWallets().length > 0
      ) &&
      this.pendingWallets().every((wallet) => this.walletAddressError(wallet.id) === null)
  );
  readonly dataSourcesChanges = computed<ReadonlyArray<DataSourcesChangeItem>>(() => {
    const settings = this.settings();
    if (!settings) {
      return [];
    }

    const changes: DataSourcesChangeItem[] = [];

    const draftWallets = this.pendingWallets().length;
    const validDraftWallets = this.validPendingWalletCount();
    if (draftWallets > 0) {
      const hasInvalid = this.hasInvalidPendingWallets();
      changes.push({
        label: hasInvalid
          ? `Add ${draftWallets} new wallet${draftWallets > 1 ? 's' : ''} (fix errors)`
          : `Add ${draftWallets} new wallet${draftWallets > 1 ? 's' : ''}`,
        tag: hasInvalid ? `${validDraftWallets}/${draftWallets}` : `+${draftWallets}`,
        tagTone: hasInvalid ? 'amber' : 'green',
      });
    }

    if (this.isActiveIntegrationCredentialsChanged()) {
      const provider = this.activeIntegrationProvider();
      const integration = this.integrationForProvider(provider);
      const label = this.providerLabel(provider);
      changes.push({
        label: integration === null ? `Connect ${label} integration` : `Update ${label} API credentials`,
        tag: label,
        tagTone: 'amber',
      });
    }

    const previousWallets = settings.wallets ?? [];
    const currentWallets = this.walletsDraft();
    if (previousWallets.length !== currentWallets.length) {
      const delta = previousWallets.length - currentWallets.length;
      if (delta > 0) {
        changes.push({
          label: `Remove ${delta} wallet${delta > 1 ? 's' : ''}`,
          tag: `-${delta}`,
          tagTone: 'red',
        });
      }
    }

    return changes;
  });

  readonly dataSourcesChangesCount = computed(() => this.dataSourcesChanges().length);
  readonly dataSourcesWalletsCount = computed(() => this.walletsDraft().length + this.validPendingWalletCount());
  readonly dataSourcesEstimatedMinMinutes = computed(() => Math.max(5, this.dataSourcesWalletsCount() * 4));
  readonly dataSourcesEstimatedMaxMinutes = computed(() => Math.max(15, this.dataSourcesWalletsCount() * 12));

  constructor() {
    this.loadSettings();

    this.integrationForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => {
      this.integrationDraft.set({
        displayName: value.displayName ?? this.providerDefaultDisplayName(this.activeIntegrationProvider()),
        apiKey: value.apiKey ?? '',
        apiSecret: value.apiSecret ?? '',
      });
    });
  }

  onIntegrationProviderSelected(provider: string): void {
    this.loadIntegrationForm(provider);
    this.testConnectionMessage.set(null);
    this.error.set(null);
  }

  onWizardCompleted(): void {
    const sessionId = this.sessionStorageService.getSessionId();
    this.sessionId.set(sessionId);
    this.loadSettings();
  }

  createEmptySession(): void {
    if (this.signingIn()) {
      return;
    }
    const sessionId = this.createSessionId();
    this.signingIn.set(true);
    this.error.set(null);
    this.saveMessage.set(null);

    this.walletApiService
      .addSession({
        sessionId,
        wallets: [],
      })
      .pipe(finalize(() => this.signingIn.set(false)))
      .subscribe({
        next: (response) => {
          const nextSessionId = response.sessionId?.trim() || sessionId;
          this.sessionStorageService.setSessionId(nextSessionId);
          this.sessionId.set(nextSessionId);
          this.loadSettings();
        },
        error: (errorResponse) => {
          this.error.set(errorResponse?.error?.message ?? 'Unable to create session.');
        },
      });
  }

  loadSettings(): void {
    const sessionId = this.sessionId();
    if (!sessionId) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.walletApiService
      .getSessionSettings(sessionId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => {
          this.applySettings(response);
        },
        error: (errorResponse) => {
          this.error.set(errorResponse?.error?.message ?? 'Unable to load session settings.');
        },
      });
  }

  setActiveSection(sectionId: SettingsSectionId): void {
    this.activeSection.set(sectionId);
    this.error.set(null);
    this.saveMessage.set(null);
  }

  startAddWallet(): void {
    if (!this.canAddMoreWallets()) {
      return;
    }
    const nextIndex = this.walletsDraft().length + this.pendingWallets().length;
    this.pendingWallets.update((wallets) => [...wallets, this.createWalletDraft(nextIndex, false)]);
    this.walletListDirty.set(true);
    this.error.set(null);
    this.saveMessage.set(null);
  }

  updatePendingWalletField(walletId: string, field: 'address' | 'label', value: string): void {
    this.pendingWallets.update((wallets) =>
      wallets.map((wallet) => {
        if (wallet.id !== walletId) return wallet;
        if (field === 'address') {
          const domain = detectWalletDomain(value.trim());
          const networks: ReadonlyArray<OnChainWalletNetworkId> =
            domain === 'SOLANA' ? ['SOLANA'] :
            domain === 'TON' ? ['TON'] :
            this.allNetworkIds;
          return { ...wallet, address: value, domain, networks };
        }
        return { ...wallet, [field]: value };
      })
    );
  }

  togglePendingWalletNetworks(walletId: string): void {
    this.pendingWallets.update((wallets) =>
      wallets.map((wallet) =>
        wallet.id === walletId
          ? {
              ...wallet,
              networksOpen: !wallet.networksOpen,
            }
          : wallet
      )
    );
  }

  toggleSavedWalletNetworks(walletId: string): void {
    this.walletsDraft.update((wallets) =>
      wallets.map((wallet) =>
        wallet.id === walletId
          ? {
              ...wallet,
              networksOpen: !wallet.networksOpen,
            }
          : wallet
      )
    );
  }

  removeWallet(walletId: string): void {
    const savedWalletExists = this.walletsDraft().some((wallet) => wallet.id === walletId);
    if (savedWalletExists) {
      this.walletsDraft.update((wallets) => wallets.filter((wallet) => wallet.id !== walletId));
      this.walletListDirty.set(true);
      return;
    }
    this.pendingWallets.update((wallets) => wallets.filter((wallet) => wallet.id !== walletId));
    this.walletListDirty.set(this.walletsDraft().length !== (this.settings()?.wallets.length ?? 0) || this.pendingWallets().length > 1);
  }

  discardPendingWallets(): void {
    this.pendingWallets.set([]);
    this.walletListDirty.set(this.walletsDraft().length !== (this.settings()?.wallets.length ?? 0));
  }

  walletAddressError(walletId: string): string | null {
    const wallet = this.pendingWallets().find((candidate) => candidate.id === walletId);
    if (!wallet) {
      return null;
    }
    const rawAddress = wallet.address.trim();
    if (rawAddress.length === 0) {
      return 'Wallet address is required';
    }
    if (!isValidWalletAddress(rawAddress)) {
      return 'Invalid wallet address (EVM, Solana, or TON)';
    }
    const normalizedAddress = normalizeWalletAddress(rawAddress);
    const duplicate = [...this.walletsDraft(), ...this.pendingWallets()].some(
      (candidate) => candidate.id !== walletId && normalizeWalletAddress(candidate.address.trim()) === normalizedAddress
    );
    if (duplicate) {
      return 'Wallet already exists in this session';
    }
    return null;
  }

  saveWallets(): void {
    if (!this.canSaveWallets()) {
      this.error.set('Fix invalid or duplicate wallet addresses before saving.');
      return;
    }
    const payload = this.tryBuildPayload(this.shouldIncludeIntegrationsInSettingsPayload());
    if (payload === null) {
      return;
    }
    this.persistSettings(payload, 'wallets');
  }

  saveGeneral(): void {
    const payload = this.tryBuildPayload(this.shouldIncludeIntegrationsInSettingsPayload());
    if (payload === null) {
      return;
    }
    this.persistSettings(payload, 'general');
  }

  saveIntegration(provider: string): void {
    this.activeIntegrationProvider.set(provider);
    const payload = this.tryBuildPayload(true);
    if (payload === null) {
      return;
    }
    this.persistSettings(payload, 'integrations');
  }

  testIntegrationConnection(provider: string): void {
    const sessionId = this.sessionId();
    if (!sessionId || this.testingConnection()) {
      return;
    }

    this.activeIntegrationProvider.set(provider);
    const apiKey = this.integrationForm.controls.apiKey.value.trim();
    const apiSecret = this.integrationForm.controls.apiSecret.value.trim();
    if (apiKey.length === 0 || apiSecret.length === 0) {
      this.testConnectionMessage.set('Enter both API key and secret to test the connection.');
      return;
    }

    this.testingConnection.set(true);
    this.testConnectionMessage.set(null);
    this.error.set(null);

    this.walletApiService
      .testIntegrationConnection(sessionId, { provider, apiKey, apiSecret })
      .pipe(finalize(() => this.testingConnection.set(false)))
      .subscribe({
        next: (response) => {
          this.testConnectionMessage.set(response.message ?? `Connected to ${response.accountRef}`);
        },
        error: (errorResponse) => {
          this.testConnectionMessage.set(
            errorResponse?.error?.message ?? 'Connection test failed.'
          );
        },
      });
  }

  disconnectIntegration(integrationId: string): void {
    // Build payload keeping all integrations EXCEPT the disconnected one.
    // Integrations are de-duped by provider server-side; empty key/secret means "keep credentials".
    const remaining = (this.settings()?.integrations ?? []).filter(
      (i) => i.integrationId !== integrationId
    );
    const seenProviders = new Set<string>();
    const integrationPayload: PutSessionSettingsRequest['integrations'][number][] = [];
    for (const i of remaining) {
      const provider = i.provider ?? 'BYBIT';
      if (!seenProviders.has(provider)) {
        seenProviders.add(provider);
        integrationPayload.push({ provider, displayName: i.displayName ?? '', apiKey: '', apiSecret: '' });
      }
    }
    const walletPayload = [...this.walletsDraft(), ...this.pendingWallets()];
    const payload: PutSessionSettingsRequest = {
      wallets: walletPayload.map((wallet, index) => ({
        address: normalizeWalletAddress(wallet.address),
        label: wallet.label.trim() || this.defaultWalletLabel(index),
        color: wallet.color,
        networks: this.resolveNetworksForWallet(wallet),
      })),
      integrations: integrationPayload,
      externalVenues: this.externalVenues(),
      hideSmallAssets: this.generalForm.controls.hideSmallAssets.value,
      showReconciliationWarnings: this.generalForm.controls.showReconciliationWarnings.value,
    };
    this.persistSettings(payload, 'integrations');
  }

  private tryBuildPayload(includeIntegrations: boolean): PutSessionSettingsRequest | null {
    try {
      return this.buildSettingsPayload(includeIntegrations);
    } catch (validation) {
      this.error.set(validation instanceof Error ? validation.message : 'Invalid integration credentials.');
      return null;
    }
  }

  resetIntegrationDraft(): void {
    this.loadIntegrationForm(this.activeIntegrationProvider());
    this.showIntegrationSecret.set(false);
    this.testConnectionMessage.set(null);
    this.error.set(null);
  }

  toggleIntegrationSecretVisibility(): void {
    this.showIntegrationSecret.update((visible) => !visible);
  }

  networkIcon(networkId: string): string {
    return this.networksPresentation.find((network) => network.id === networkId)?.icon ?? '•';
  }

  shortAddress(address: string): string {
    return `${address.slice(0, 6)}…${address.slice(-4)}`;
  }

  validPendingWalletCount(): number {
    return this.pendingWallets().filter((wallet) => this.walletAddressError(wallet.id) === null).length;
  }

  hasInvalidPendingWallets(): boolean {
    return this.pendingWallets().some((wallet) => this.walletAddressError(wallet.id) !== null);
  }

  openDataSourcesReview(): void {
    if (this.dataSourcesChangesCount() === 0) {
      return;
    }
    this.dataSourcesReviewOpen.set(true);
    this.error.set(null);
    this.saveMessage.set(null);
  }

  closeDataSourcesReview(): void {
    this.dataSourcesReviewOpen.set(false);
  }

  confirmDataSourcesSave(): void {
    const sessionId = this.sessionId();
    if (!sessionId || this.dataSourcesSaving() || this.isSaving()) {
      return;
    }

    if (this.hasInvalidPendingWallets()) {
      this.error.set('Fix invalid or duplicate wallet addresses before saving.');
      return;
    }

    let payload: PutSessionSettingsRequest;
    try {
      payload = this.buildSettingsPayload(this.shouldIncludeIntegrationsInSettingsPayload());
    } catch (validation) {
      this.error.set(validation instanceof Error ? validation.message : 'Invalid integration credentials.');
      return;
    }

    this.dataSourcesSaving.set(true);
    this.error.set(null);
    this.saveMessage.set(null);

    this.walletApiService
      .putSessionSettings(sessionId, payload)
      .pipe(
        switchMap(() =>
          this.walletApiService.refreshSession(sessionId).pipe(
            // 409 = refresh blocked while backfill is in progress — settings are saved, skip restart
            catchError((err) => (err?.status === 409 ? of(null) : ((() => { throw err; })()))),
          )
        ),
        switchMap(() => this.walletApiService.getSessionSettings(sessionId)),
        tap((settings) => this.applySettings(settings)),
        finalize(() => this.dataSourcesSaving.set(false))
      )
      .subscribe({
        next: () => {
          this.dataSourcesReviewOpen.set(false);
          this.saveMessage.set('Changes saved.');
        },
        error: (errorResponse) => {
          this.error.set(errorResponse?.error?.message ?? 'Unable to save changes.');
        },
      });
  }

  statusTone(integration: SessionIntegrationResponse | null): StatusTone {
    if (!integration?.status) {
      return 'idle';
    }
    if (integration.status === 'READY' || integration.status === 'CONNECTED') {
      return 'ready';
    }
    if (integration.status === 'ERROR') {
      return 'error';
    }
    return 'busy';
  }

  hasIntegrationPayload(provider: string): boolean {
    if (this.activeIntegrationProvider() !== provider) {
      return false;
    }
    const integration = this.integrationForProvider(provider);
    const apiKey = this.integrationForm.controls.apiKey.value.trim();
    const apiSecret = this.integrationForm.controls.apiSecret.value.trim();
    if (integration !== null) {
      return (apiKey.length === 0 && apiSecret.length === 0) || (apiKey.length > 0 && apiSecret.length > 0);
    }
    return apiKey.length > 0 && apiSecret.length > 0;
  }

  /** Keep existing integrations on any save; include new credentials when both key and secret are filled. */
  private shouldIncludeIntegrationsInSettingsPayload(): boolean {
    const activeProvider = this.activeIntegrationProvider();
    return this.integrationForProvider(activeProvider) !== null || this.hasIntegrationPayload(activeProvider);
  }

  private isActiveIntegrationCredentialsChanged(): boolean {
    const draft = this.integrationDraft();
    const snapshot = this.integrationInitialSnapshot();
    if (snapshot.provider !== this.activeIntegrationProvider()) {
      return false;
    }
    const apiKey = draft.apiKey.trim();
    const apiSecret = draft.apiSecret.trim();
    if (apiSecret.length > 0) {
      return true;
    }
    return apiKey !== snapshot.apiKey.trim() && apiKey.length > 0;
  }

  private buildSettingsPayload(includeIntegrations: boolean): PutSessionSettingsRequest {
    const walletPayload = [...this.walletsDraft(), ...this.pendingWallets()];
    return {
      wallets: walletPayload.map((wallet, index) => ({
        address: normalizeWalletAddress(wallet.address),
        label: wallet.label.trim() || this.defaultWalletLabel(index),
        color: wallet.color,
        networks: this.resolveNetworksForWallet(wallet),
      })),
      integrations: includeIntegrations ? this.buildIntegrationsPayload() : this.buildPreservedIntegrationsPayload(),
      externalVenues: this.externalVenues(),
      hideSmallAssets: this.generalForm.controls.hideSmallAssets.value,
      showReconciliationWarnings: this.generalForm.controls.showReconciliationWarnings.value,
    };
  }

  private buildPreservedIntegrationsPayload(): PutSessionSettingsRequest['integrations'] {
    return (this.settings()?.integrations ?? []).map((integration) => ({
      provider: integration.provider ?? 'BYBIT',
      displayName: integration.displayName ?? '',
      apiKey: '',
      apiSecret: '',
    }));
  }

  private buildIntegrationsPayload(): PutSessionSettingsRequest['integrations'] {
    const existing = this.settings()?.integrations ?? [];
    const activeProvider = this.activeIntegrationProvider();
    const payload: PutSessionSettingsRequest['integrations'][number][] = [];
    const seenProviders = new Set<string>();

    for (const integration of existing) {
      const provider = integration.provider ?? 'BYBIT';
      const isFirstOfActiveProvider = provider === activeProvider && !seenProviders.has(activeProvider);
      seenProviders.add(provider);

      if (isFirstOfActiveProvider) {
        // Only apply form data to the first account of the active provider.
        const entry = this.buildIntegrationEntry(provider);
        if (entry !== null) {
          payload.push(entry);
        } else {
          payload.push({
            provider,
            displayName: integration.displayName ?? '',
            apiKey: '',
            apiSecret: '',
          });
        }
      } else {
        // Preserve original data for all other accounts (same or different provider).
        payload.push({
          provider,
          displayName: integration.displayName ?? '',
          apiKey: '',
          apiSecret: '',
        });
      }
    }

    if (!seenProviders.has(activeProvider) && this.hasIntegrationPayload(activeProvider)) {
      const entry = this.buildIntegrationEntry(activeProvider);
      if (entry !== null) {
        payload.push(entry);
      }
    }

    return payload;
  }

  private buildIntegrationEntry(
    provider: string
  ): PutSessionSettingsRequest['integrations'][number] | null {
    const integration = this.integrationForProvider(provider);
    const draft = this.integrationDraft();
    const snapshot = this.integrationInitialSnapshot();
    const apiKey = draft.apiKey.trim();
    const apiSecret = draft.apiSecret.trim();
    const label = this.providerLabel(provider);
    const displayName =
      this.integrationForm.controls.displayName.value.trim() ||
      integration?.displayName ||
      this.providerDefaultDisplayName(provider);

    if (integration === null) {
      if (apiKey.length === 0 && apiSecret.length === 0) {
        return null;
      }
      if (apiKey.length === 0 || apiSecret.length === 0) {
        throw new Error(`Enter both API key and API secret to connect ${label}.`);
      }
      const color = pickUnusedIntegrationColor(this.allIntegrations().map((item) => item.color));
      return { provider, displayName, apiKey, apiSecret, color };
    }

    if (!this.isActiveIntegrationCredentialsChanged()) {
      return { provider, displayName, apiKey: '', apiSecret: '' };
    }

    const snapshotKey = snapshot.apiKey.trim();
    if (apiKey.length === 0 || apiSecret.length === 0 || apiKey === snapshotKey) {
      throw new Error(`To update ${label} credentials, enter both new API key and secret.`);
    }
    return { provider, displayName, apiKey, apiSecret };
  }

  private integrationForProvider(provider: string): SessionIntegrationResponse | null {
    return this.settings()?.integrations.find((integration) => integration.provider === provider) ?? null;
  }

  private providerLabel(provider: string): string {
    return AVAILABLE_PROVIDERS.find((candidate) => candidate.id === provider)?.label ?? provider;
  }

  private providerDefaultDisplayName(provider: string): string {
    return AVAILABLE_PROVIDERS.find((candidate) => candidate.id === provider)?.defaultDisplayName ?? provider;
  }

  private loadIntegrationForm(provider: string): void {
    const integration = this.integrationForProvider(provider);
    const initialApiKey = integration?.maskedKey ?? '';
    const displayName = integration?.displayName ?? this.providerDefaultDisplayName(provider);
    this.activeIntegrationProvider.set(provider);
    this.integrationForm.reset(
      {
        displayName,
        apiKey: initialApiKey,
        apiSecret: '',
      },
      { emitEvent: false }
    );
    this.integrationDraft.set({ displayName, apiKey: initialApiKey, apiSecret: '' });
    this.integrationInitialSnapshot.set({ provider, apiKey: initialApiKey });
    this.showIntegrationSecret.set(false);
  }

  private persistSettings(payload: PutSessionSettingsRequest, scope: SettingsSaveScope): void {
    const sessionId = this.sessionId();
    if (!sessionId || this.isSaving()) {
      return;
    }
    this.savingScope.set(scope);
    this.error.set(null);
    this.saveMessage.set(null);

    this.walletApiService
      .putSessionSettings(sessionId, payload)
      .pipe(finalize(() => this.savingScope.set(null)))
      .subscribe({
        next: (response) => {
          this.applySettings(response);
          this.saveMessage.set(this.successMessage(scope, response));
        },
        error: (errorResponse) => {
          this.error.set(errorResponse?.error?.message ?? 'Unable to save session settings.');
        },
      });
  }

  private successMessage(scope: SettingsSaveScope, response: SessionSettingsResponse): string {
    switch (scope) {
      case 'wallets':
        return `${response.wallets.length} wallet configuration saved`;
      case 'general':
        return 'General settings saved';
      case 'integrations':
        return this.allIntegrations().length === 0 ? 'Integration removed' : 'Integration settings saved';
      default:
        return 'Settings saved';
    }
  }

  private applySettings(response: SessionSettingsResponse): void {
    this.settings.set(response);
    this.walletsDraft.set(
      response.wallets.map((wallet) => {
        const domain = detectWalletDomain(wallet.address);
        const networks: ReadonlyArray<OnChainWalletNetworkId> =
          domain === 'EVM' ? [...this.allNetworkIds] :
          wallet.networks.length > 0 ? [...wallet.networks] :
          [...this.allNetworkIds];
        const colorIndex = response.wallets.indexOf(wallet);
        return {
          id: this.createWalletDraftId(),
          address: normalizeWalletAddress(wallet.address),
          label: wallet.label ?? '',
          color: (wallet.color ?? WALLET_COLOR_PALETTE[colorIndex % WALLET_COLOR_PALETTE.length] ?? WALLET_COLOR_PALETTE[0]!).toLowerCase(),
          networks,
          networksOpen: false,
          domain,
        };
      })
    );
    this.pendingWallets.set([]);
    this.walletListDirty.set(false);
    this.externalVenues.set(response.externalVenues ?? []);
    this.generalForm.reset(
      {
        hideSmallAssets: response.hideSmallAssets ?? true,
        showReconciliationWarnings: response.showReconciliationWarnings ?? true,
      },
      { emitEvent: false }
    );

    const firstIntegration = response.integrations[0];
    const initialProvider = firstIntegration?.provider ?? 'BYBIT';
    this.loadIntegrationForm(initialProvider);
  }

  private createWalletDraft(index: number, networksOpen = false): SettingsWalletDraft {
    const usedColors = [...this.walletsDraft(), ...this.pendingWallets()].map((w) => w.color);
    const color = pickUnusedColor(WALLET_COLOR_PALETTE, usedColors);
    return {
      id: this.createWalletDraftId(),
      address: '',
      label: this.defaultWalletLabel(index),
      color,
      networks: [...this.allNetworkIds],
      networksOpen,
      domain: 'UNKNOWN',
    };
  }

  private resolveNetworksForWallet(wallet: SettingsWalletDraft): ReadonlyArray<OnChainWalletNetworkId> {
    if (wallet.domain === 'SOLANA') return ['SOLANA'];
    if (wallet.domain === 'TON') return ['TON'];
    return this.onChainNetworkIds;
  }

  private defaultWalletLabel(index: number): string {
    return `Wallet ${index + 1}`;
  }

  private createWalletDraftId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  private createSessionId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
      const random = Math.floor(Math.random() * 16);
      const value = char === 'x' ? random : (random & 0x3) | 0x8;
      return value.toString(16);
    });
  }
}
