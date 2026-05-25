import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Output, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, switchMap, tap } from 'rxjs';

import { COLORS, EVM_NETWORKS_PRESENTATION } from '../../../core/data/dashboard.constants';
import {
  AddSessionRequestItem,
  EvmNetworkId,
  OnChainWalletNetworkId,
  PutSessionSettingsRequest,
} from '../../../core/models/wallet-api.models';
import { SessionStorageService } from '../../../core/services/session-storage.service';
import { WalletApiService } from '../../../core/services/wallet-api.service';

type WizardStepId = 'wallets' | 'integrations' | 'accounting' | 'review';

interface WizardStepMeta {
  readonly id: WizardStepId;
  readonly icon: string;
  readonly label: string;
  readonly desc: string;
}

interface WizardWalletDraft {
  readonly id: string;
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<EvmNetworkId>;
  readonly networksOpen: boolean;
}

const WIZARD_STEPS: ReadonlyArray<WizardStepMeta> = [
  { id: 'wallets', icon: '◍', label: 'Wallets', desc: 'Add EVM addresses to track' },
  { id: 'integrations', icon: '⇄', label: 'Integrations', desc: 'Optional exchange connections' },
  { id: 'accounting', icon: '◈', label: 'Accounting', desc: 'Cost basis method' },
  { id: 'review', icon: '✓', label: 'Review', desc: 'Confirm & start indexing' },
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
  selector: 'wr-settings-wizard',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './settings-wizard.component.html',
  styleUrl: './settings-wizard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsWizardComponent {
  @Output() readonly completed = new EventEmitter<void>();

  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);
  private readonly formBuilder = inject(FormBuilder);

  readonly networksPresentation = EVM_NETWORKS_PRESENTATION;
  readonly allNetworkIds: ReadonlyArray<EvmNetworkId> = this.networksPresentation.map((network) => network.id);

  readonly maxWallets = 10;
  readonly steps = WIZARD_STEPS;
  readonly activeStep = signal<WizardStepId>('wallets');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly wallets = signal<ReadonlyArray<WizardWalletDraft>>([this.createWalletDraft(0)]);

  readonly bybitForm = this.formBuilder.nonNullable.group({
    displayName: ['Bybit'],
    apiKey: [''],
    apiSecret: [''],
  });

  readonly accountingMethod = signal<'AVCO'>('AVCO');

  readonly validWallets = computed(() =>
    this.wallets()
      .map((wallet) => ({
        ...wallet,
        address: wallet.address.trim(),
        label: wallet.label.trim(),
      }))
      .filter((wallet) => EVM_ADDRESS_PATTERN.test(wallet.address))
      .filter((wallet) => wallet.networks.length > 0)
  );

  readonly hasWalletErrors = computed(() =>
    this.wallets().some((wallet) => {
      const address = wallet.address.trim();
      if (address.length === 0) {
        return false;
      }
      return !EVM_ADDRESS_PATTERN.test(address) || wallet.networks.length === 0;
    })
  );

  readonly canProceed = computed(() => {
    switch (this.activeStep()) {
      case 'wallets':
        return this.validWallets().length > 0 && !this.hasWalletErrors();
      case 'integrations':
      case 'accounting':
      case 'review':
        return true;
    }
  });

  setStep(stepId: WizardStepId): void {
    if (this.busy()) {
      return;
    }
    this.error.set(null);
    this.activeStep.set(stepId);
  }

  next(): void {
    const index = this.steps.findIndex((step) => step.id === this.activeStep());
    if (index < 0 || index === this.steps.length - 1) {
      return;
    }
    if (!this.canProceed()) {
      return;
    }
    this.setStep(this.steps[index + 1]!.id);
  }

  prev(): void {
    const index = this.steps.findIndex((step) => step.id === this.activeStep());
    if (index <= 0) {
      return;
    }
    this.setStep(this.steps[index - 1]!.id);
  }

  addWallet(): void {
    if (this.busy()) {
      return;
    }
    if (this.wallets().length >= this.maxWallets) {
      return;
    }
    const nextIndex = this.wallets().length;
    this.wallets.update((wallets) => [...wallets, this.createWalletDraft(nextIndex)]);
  }

  removeWallet(walletId: string): void {
    if (this.busy()) {
      return;
    }
    if (this.wallets().length <= 1) {
      return;
    }
    this.wallets.update((wallets) => wallets.filter((wallet) => wallet.id !== walletId));
  }

  updateWallet(walletId: string, patch: Partial<Pick<WizardWalletDraft, 'address' | 'label'>>): void {
    this.wallets.update((wallets) =>
      wallets.map((wallet) => (wallet.id === walletId ? { ...wallet, ...patch } : wallet))
    );
  }

  toggleWalletNetworks(walletId: string): void {
    this.wallets.update((wallets) =>
      wallets.map((wallet) =>
        wallet.id === walletId ? { ...wallet, networksOpen: !wallet.networksOpen } : wallet
      )
    );
  }

  toggleWalletNetwork(walletId: string, networkId: EvmNetworkId): void {
    this.wallets.update((wallets) =>
      wallets.map((wallet) => {
        if (wallet.id !== walletId) {
          return wallet;
        }
        const enabled = wallet.networks.includes(networkId);
        const nextNetworks = enabled
          ? wallet.networks.filter((value) => value !== networkId)
          : [...wallet.networks, networkId];
        return { ...wallet, networks: nextNetworks };
      })
    );
  }

  setAllNetworks(walletId: string, enabled: boolean): void {
    this.wallets.update((wallets) =>
      wallets.map((wallet) =>
        wallet.id === walletId ? { ...wallet, networks: enabled ? [...this.allNetworkIds] : [] } : wallet
      )
    );
  }

  handleAddressPaste(event: ClipboardEvent, rowIndex: number): void {
    if (this.busy()) {
      return;
    }
    const text = event.clipboardData?.getData('text') ?? '';
    const candidates = text
      .split(/[\n,\s]+/gu)
      .map((value) => value.trim())
      .filter((value) => value.length > 10);
    if (candidates.length <= 1) {
      return;
    }
    event.preventDefault();

    const rows = candidates.slice(0, this.maxWallets).map((address, index) => {
      const draft = this.createWalletDraft(rowIndex + index);
      return { ...draft, address };
    });
    this.wallets.update((existing) => {
      const next = [...existing];
      next.splice(rowIndex, 1, ...rows);
      return next.slice(0, this.maxWallets);
    });
  }

  submit(): void {
    if (this.busy() || this.activeStep() !== 'review') {
      return;
    }
    this.busy.set(true);
    this.error.set(null);

    const sessionId = this.createSessionId();
    const walletsPayload: ReadonlyArray<AddSessionRequestItem> = this.validWallets().map((wallet) => ({
      address: wallet.address.trim(),
      label: wallet.label.trim(),
      color: wallet.color,
      networks: wallet.networks.filter((network) => network !== 'BYBIT') as ReadonlyArray<OnChainWalletNetworkId>,
    }));

    const payload: PutSessionSettingsRequest = {
      wallets: walletsPayload,
      integrations: this.bybitIntegrationsPayload(),
      externalVenues: [],
      hideSmallAssets: true,
      showReconciliationWarnings: true,
    };

    this.walletApiService
      .addSession({ sessionId, wallets: walletsPayload })
      .pipe(
        tap((response) => {
          const persistedSessionId = response.sessionId?.trim() || sessionId;
          this.sessionStorageService.setSessionId(persistedSessionId);
        }),
        switchMap((response) => {
          const persistedSessionId = response.sessionId?.trim() || sessionId;
          return this.walletApiService.putSessionSettings(persistedSessionId, payload);
        }),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: () => {
          this.completed.emit();
        },
        error: (errorResponse) => {
          this.error.set(errorResponse?.error?.message ?? 'Unable to create session.');
        },
      });
  }

  walletAddressError(wallet: WizardWalletDraft): string | null {
    const address = wallet.address.trim();
    if (address.length === 0) {
      return null;
    }
    if (!EVM_ADDRESS_PATTERN.test(address)) {
      return 'Not a valid EVM address (0x + 40 hex chars).';
    }
    if (wallet.networks.length === 0) {
      return 'Select at least one network.';
    }
    return null;
  }

  isStepComplete(stepId: WizardStepId): boolean {
    if (stepId === 'wallets') {
      return this.validWallets().length > 0 && !this.hasWalletErrors();
    }
    if (stepId === 'integrations') {
      return true;
    }
    if (stepId === 'accounting') {
      return true;
    }
    return false;
  }

  private bybitIntegrationsPayload(): PutSessionSettingsRequest['integrations'] {
    const apiKey = this.bybitForm.controls.apiKey.value.trim();
    const apiSecret = this.bybitForm.controls.apiSecret.value.trim();
    if (apiKey.length === 0 && apiSecret.length === 0) {
      return [];
    }
    return [
      {
        provider: 'BYBIT',
        displayName: this.bybitForm.controls.displayName.value.trim() || 'Bybit',
        apiKey,
        apiSecret,
      },
    ];
  }

  private createWalletDraft(index: number): WizardWalletDraft {
    return {
      id: this.createDraftId(),
      address: '',
      label: this.defaultWalletLabel(index),
      color: WALLET_COLOR_PALETTE[index % WALLET_COLOR_PALETTE.length]!,
      networks: [...this.allNetworkIds],
      networksOpen: false,
    };
  }

  private defaultWalletLabel(index: number): string {
    return `Wallet ${index + 1}`;
  }

  private createDraftId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  private createSessionId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/gu, (char) => {
      const random = Math.floor(Math.random() * 16);
      const value = char === 'x' ? random : (random & 0x3) | 0x8;
      return value.toString(16);
    });
  }
}

