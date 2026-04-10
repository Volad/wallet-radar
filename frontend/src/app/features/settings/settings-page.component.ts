import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';

import { COLORS, EVM_NETWORKS_PRESENTATION } from '../../core/data/dashboard.constants';
import {
  EvmNetworkId,
  PutSessionSettingsRequest,
  SessionIntegrationResponse,
  SessionSettingsResponse,
} from '../../core/models/wallet-api.models';
import { SessionStorageService } from '../../core/services/session-storage.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { formatDateTimeWithSeconds } from '../../core/utils/date-time.util';

type SettingsSectionId = 'wallets' | 'integrations' | 'general';
type SettingsSaveScope = SettingsSectionId;
type StatusTone = 'ready' | 'busy' | 'error' | 'idle';

interface SettingsSectionMeta {
  readonly id: SettingsSectionId;
  readonly icon: string;
  readonly label: string;
}

interface SettingsWalletDraft {
  readonly id: string;
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<EvmNetworkId>;
  readonly networksOpen: boolean;
}

const SETTINGS_SECTIONS: ReadonlyArray<SettingsSectionMeta> = [
  { id: 'wallets', icon: '◍', label: 'Wallets & Networks' },
  { id: 'integrations', icon: '⇄', label: 'Integrations' },
  { id: 'general', icon: '◈', label: 'General' },
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

const EVM_ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/u;

@Component({
  selector: 'wr-settings-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './settings-page.component.html',
  styleUrl: './settings-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPageComponent {
  readonly maxWallets = 10;
  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);
  private readonly formBuilder = inject(FormBuilder);

  readonly sections = SETTINGS_SECTIONS;
  readonly networksPresentation = EVM_NETWORKS_PRESENTATION;
  readonly allNetworkIds: ReadonlyArray<EvmNetworkId> = this.networksPresentation.map((network) => network.id);

  readonly sessionId = signal<string | null>(this.sessionStorageService.getSessionId());
  readonly settings = signal<SessionSettingsResponse | null>(null);
  readonly walletsDraft = signal<ReadonlyArray<SettingsWalletDraft>>([]);
  readonly pendingWallets = signal<ReadonlyArray<SettingsWalletDraft>>([]);
  readonly activeSection = signal<SettingsSectionId>('wallets');
  readonly loading = signal(false);
  readonly savingScope = signal<SettingsSaveScope | null>(null);
  readonly error = signal<string | null>(null);
  readonly saveMessage = signal<string | null>(null);
  readonly showBybitSecret = signal(false);
  readonly signingIn = signal(false);
  readonly walletListDirty = signal(false);

  readonly generalForm = this.formBuilder.nonNullable.group({
    hideSmallAssets: [true],
    showReconciliationWarnings: [true],
  });

  readonly bybitForm = this.formBuilder.nonNullable.group({
    displayName: ['Bybit'],
    apiKey: [''],
    apiSecret: [''],
  });

  readonly hasSession = computed(() => this.sessionId() !== null);
  readonly bybitIntegration = computed(
    () => this.settings()?.integrations.find((integration) => integration.provider === 'BYBIT') ?? null
  );
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
  readonly sectionBadge = computed(() => ({
    wallets: this.walletCount(),
    integrations: this.settings()?.integrations.length ?? 0,
  }));

  constructor() {
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
      wallets.map((wallet) =>
        wallet.id === walletId
          ? {
              ...wallet,
              [field]: value,
            }
          : wallet
      )
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
    if (!EVM_ADDRESS_PATTERN.test(rawAddress)) {
      return 'Invalid EVM address';
    }
    const normalizedAddress = rawAddress.toLowerCase();
    const duplicate = [...this.walletsDraft(), ...this.pendingWallets()].some(
      (candidate) => candidate.id !== walletId && candidate.address.trim().toLowerCase() === normalizedAddress
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
    this.persistSettings(this.buildSettingsPayload(this.bybitIntegration() !== null), 'wallets');
  }

  saveGeneral(): void {
    this.persistSettings(this.buildSettingsPayload(this.bybitIntegration() !== null), 'general');
  }

  saveBybit(): void {
    if (!this.hasBybitPayload()) {
      this.error.set('Enter both API key and API secret to connect Bybit.');
      return;
    }
    this.persistSettings(this.buildSettingsPayload(true), 'integrations');
  }

  disconnectBybit(): void {
    this.persistSettings(this.buildSettingsPayload(false), 'integrations');
  }

  resetBybitDraft(): void {
    const bybit = this.bybitIntegration();
    this.bybitForm.reset(
      {
        displayName: bybit?.displayName ?? 'Bybit',
        apiKey: '',
        apiSecret: '',
      },
      { emitEvent: false }
    );
    this.showBybitSecret.set(false);
    this.error.set(null);
  }

  toggleBybitSecretVisibility(): void {
    this.showBybitSecret.update((visible) => !visible);
  }

  networkIcon(networkId: EvmNetworkId): string {
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

  hasBybitPayload(): boolean {
    const integration = this.bybitIntegration();
    if (integration !== null) {
      const apiKey = this.bybitForm.controls.apiKey.value.trim();
      const apiSecret = this.bybitForm.controls.apiSecret.value.trim();
      return (apiKey.length === 0 && apiSecret.length === 0) || (apiKey.length > 0 && apiSecret.length > 0);
    }
    return (
      this.bybitForm.controls.apiKey.value.trim().length > 0 &&
      this.bybitForm.controls.apiSecret.value.trim().length > 0
    );
  }

  private buildSettingsPayload(includeBybit: boolean): PutSessionSettingsRequest {
    const walletPayload = [...this.walletsDraft(), ...this.pendingWallets()];
    return {
      wallets: walletPayload.map((wallet, index) => ({
        address: wallet.address.trim().toLowerCase(),
        label: wallet.label.trim() || this.defaultWalletLabel(index),
        color: wallet.color,
        networks: [...this.allNetworkIds],
      })),
      integrations: includeBybit
        ? [
            {
              provider: 'BYBIT',
              displayName:
                this.bybitForm.controls.displayName.value.trim() ||
                this.bybitIntegration()?.displayName ||
                'Bybit',
              apiKey: this.bybitForm.controls.apiKey.value.trim(),
              apiSecret: this.bybitForm.controls.apiSecret.value.trim(),
            },
          ]
        : [],
      hideSmallAssets: this.generalForm.controls.hideSmallAssets.value,
      showReconciliationWarnings: this.generalForm.controls.showReconciliationWarnings.value,
    };
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
          this.bybitForm.controls.apiKey.reset('');
          this.bybitForm.controls.apiSecret.reset('');
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
        return this.bybitIntegration() === null ? 'Integration removed' : 'Integration settings saved';
      default:
        return 'Settings saved';
    }
  }

  private applySettings(response: SessionSettingsResponse): void {
    this.settings.set(response);
    this.walletsDraft.set(
      response.wallets.map((wallet, index) => ({
        id: this.createWalletDraftId(),
        address: wallet.address.toLowerCase(),
        label: wallet.label,
        color: wallet.color.toLowerCase(),
        networks: [...this.allNetworkIds],
        networksOpen: false,
      }))
    );
    this.pendingWallets.set([]);
    this.walletListDirty.set(false);
    this.generalForm.reset(
      {
        hideSmallAssets: response.hideSmallAssets ?? true,
        showReconciliationWarnings: response.showReconciliationWarnings ?? true,
      },
      { emitEvent: false }
    );

    const bybit = response.integrations.find((integration) => integration.provider === 'BYBIT') ?? null;
    this.bybitForm.reset(
      {
        displayName: bybit?.displayName ?? 'Bybit',
        apiKey: '',
        apiSecret: '',
      },
      { emitEvent: false }
    );
  }

  private createWalletDraft(index: number, networksOpen = false): SettingsWalletDraft {
    return {
      id: this.createWalletDraftId(),
      address: '',
      label: this.defaultWalletLabel(index),
      color: WALLET_COLOR_PALETTE[index % WALLET_COLOR_PALETTE.length]!,
      networks: [...this.allNetworkIds],
      networksOpen,
    };
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
