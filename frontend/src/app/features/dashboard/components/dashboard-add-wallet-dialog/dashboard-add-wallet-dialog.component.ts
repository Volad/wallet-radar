import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  NonNullableFormBuilder,
  Validators,
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { COLORS } from '../../../../core/data/dashboard.constants';
import { WalletInfo } from '../../../../core/models/dashboard.models';
import { AddSessionRequestItem, EvmNetworkId, OnChainWalletNetworkId } from '../../../../core/models/wallet-api.models';

export type WalletSubmitState = 'idle' | 'submitting' | 'success' | 'error';

export interface EvmNetworkPresentation {
  readonly id: EvmNetworkId;
  readonly icon: string;
  readonly label: string;
  readonly color: string;
}

type WalletAddressState = 'empty' | 'ok' | 'warn' | 'error';
type WalletKind = 'evm' | 'solana' | 'ton';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;

interface WalletAddressEvaluation {
  readonly state: WalletAddressState;
  readonly message: string | null;
  readonly kind: WalletKind | null;
}

const EVM_ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/iu;
const SOLANA_ADDRESS_PATTERN = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/u;
const TON_FRIENDLY_ADDRESS_PATTERN = /^(?:UQ|EQ)[A-Za-z0-9_-]{46}$/u;
const WALLET_COLOR_PALETTE: ReadonlyArray<string> = [
  COLORS.cyan,
  COLORS.purple,
  COLORS.green,
  COLORS.amber,
  '#60a5fa',
  '#f472b6',
  '#34d399',
];
const MAX_WALLET_ROWS = 10;

@Component({
  selector: 'wr-dashboard-add-wallet-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './dashboard-add-wallet-dialog.component.html',
  styleUrl: './dashboard-add-wallet-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardAddWalletDialogComponent implements OnChanges {
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  @Input({ required: true }) isOpen = false;
  @Input({ required: true }) isSubmitBusy = false;
  @Input({ required: true }) submitState: WalletSubmitState = 'idle';
  @Input({ required: true }) submitMessage: string | null = null;
  @Input({ required: true }) existingWallets: ReadonlyArray<WalletInfo> = [];
  @Input({ required: true }) supportedEvmNetworks: ReadonlyArray<EvmNetworkId> = [];
  @Input({ required: true }) evmNetworksPresentation: ReadonlyArray<EvmNetworkPresentation> = [];

  @Output() close = new EventEmitter<void>();
  @Output() submitWallets = new EventEmitter<ReadonlyArray<AddSessionRequestItem>>();

  readonly addWalletsForm = this.formBuilder.group({
    wallets: this.formBuilder.array([this.createWalletFormGroup(0)]),
  });
  readonly localSubmitMessage = signal<string | null>(null);
  private readonly formValueVersion = signal(0);

  readonly walletRows = computed(() => this.walletFormArray.controls);
  readonly walletValidationSummary = computed(() => {
    this.formValueVersion();
    let filled = 0;
    let ready = 0;

    this.walletRows().forEach((walletGroup, index) => {
      const evaluation = this.evaluateWalletAddress(walletGroup.controls.address, index);
      if (evaluation.state === 'empty') {
        return;
      }

      filled += 1;
      if (evaluation.state === 'ok' || evaluation.state === 'warn') {
        ready += 1;
      }
    });

    return { filled, ready };
  });

  readonly effectiveSubmitMessage = computed(() => this.submitMessage ?? this.localSubmitMessage());

  constructor() {
    this.addWalletsForm.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.formValueVersion.update((version) => version + 1);
      });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isOpen']?.currentValue === true && changes['isOpen']?.previousValue !== true) {
      this.resetForm();
      this.localSubmitMessage.set(null);
    }
    if (changes['submitMessage'] && this.submitMessage !== null) {
      this.localSubmitMessage.set(null);
    }
  }

  canSubmitWallets(): boolean {
    const summary = this.walletValidationSummary();
    return summary.ready > 0 && !this.isSubmitBusy;
  }

  addWalletField(): void {
    if (this.walletFormArray.length >= MAX_WALLET_ROWS) {
      return;
    }
    this.walletFormArray.push(this.createWalletFormGroup(this.walletFormArray.length));
  }

  removeWalletField(index: number): void {
    if (this.walletFormArray.length <= 1) {
      return;
    }
    this.walletFormArray.removeAt(index);
  }

  walletAddressState(control: FormControl<string>, index: number): WalletAddressState {
    return this.evaluateWalletAddress(control, index).state;
  }

  walletAddressMessage(control: FormControl<string>, index: number): string | null {
    return this.evaluateWalletAddress(control, index).message;
  }

  showWalletAddressMessage(control: FormControl<string>, index: number): boolean {
    const evaluation = this.evaluateWalletAddress(control, index);
    if (evaluation.message === null) {
      return false;
    }
    if (evaluation.state === 'warn') {
      return true;
    }

    return control.touched || control.dirty;
  }

  onSubmit(): void {
    if (!this.canSubmitWallets()) {
      this.addWalletsForm.markAllAsTouched();
      if (this.walletValidationSummary().ready === 0) {
        this.localSubmitMessage.set('Add at least one valid wallet address.');
      }
      return;
    }

    this.localSubmitMessage.set(null);
    this.submitWallets.emit(this.buildWalletItems());
  }

  onAddressPaste(event: ClipboardEvent, startIndex: number): void {
    const raw = event.clipboardData?.getData('text') ?? '';
    if (raw.trim().length === 0) {
      return;
    }

    const parsedAddresses = raw
      .split(/[\n,]+/u)
      .map((value) => value.trim())
      .filter((value) => value.length > 0);

    if (parsedAddresses.length <= 1) {
      return;
    }

    event.preventDefault();
    this.applyPastedAddresses(parsedAddresses, startIndex);
  }

  trackByIndex(index: number): number {
    return index;
  }

  private get walletFormArray(): FormArray<WalletFormGroup> {
    return this.addWalletsForm.controls.wallets;
  }

  private createWalletFormGroup(index: number): WalletFormGroup {
    return this.formBuilder.group({
      address: this.formBuilder.control(''),
      label: this.formBuilder.control(`Wallet ${index + 1}`),
      color: this.formBuilder.control(WALLET_COLOR_PALETTE[index % WALLET_COLOR_PALETTE.length]),
    });
  }

  private evaluateWalletAddress(control: FormControl<string>, index: number): WalletAddressEvaluation {
    const address = control.value.trim();
    if (address.length === 0) {
      return { state: 'empty', message: null, kind: null };
    }

    const kind = detectWalletKind(address);
    if (kind === null) {
      return { state: 'error', message: 'Invalid EVM, Solana, or TON address', kind: null };
    }

    const scopeId = walletScopeId(address, kind);
    const isDuplicateInInput = this.walletFormArray.controls.some((walletGroup, currentIndex) => {
      if (currentIndex === index) {
        return false;
      }
      const other = walletGroup.controls.address.value.trim();
      if (other.length === 0) {
        return false;
      }
      const otherKind = detectWalletKind(other);
      return otherKind !== null && walletScopeId(other, otherKind) === scopeId;
    });
    if (isDuplicateInInput) {
      return { state: 'error', message: 'Duplicate address in current list', kind };
    }

    const isExistingWallet = this.existingWallets.some((wallet) => {
      const existingKind = detectWalletKind(wallet.address.trim());
      return existingKind !== null && walletScopeId(wallet.address.trim(), existingKind) === scopeId;
    });
    if (isExistingWallet) {
      return {
        state: 'warn',
        message: kind === 'evm'
            ? 'Already tracked — new networks will be added'
            : 'Already tracked — used for transfer linking (no backfill)',
        kind,
      };
    }

    if (kind === 'solana' || kind === 'ton') {
      return {
        state: 'ok',
        message: 'Tracked for transfer linking only (no on-chain backfill)',
        kind,
      };
    }

    return { state: 'ok', message: null, kind };
  }

  private buildWalletItems(): ReadonlyArray<AddSessionRequestItem> {
    return this.walletFormArray.controls
      .map((walletForm, index) => {
        const evaluation = this.evaluateWalletAddress(walletForm.controls.address, index);
        const networks = networksForWalletKind(evaluation.kind, this.supportedEvmNetworks);
        return {
          state: evaluation.state,
          address: walletForm.controls.address.value.trim(),
          label: walletForm.controls.label.value.trim() || `Wallet ${index + 1}`,
          color: walletForm.controls.color.value,
          networks,
        };
      })
      .filter((wallet) => wallet.state === 'ok' || wallet.state === 'warn')
      .map((wallet) => ({
        address: wallet.address,
        label: wallet.label,
        color: wallet.color,
        networks: wallet.networks,
      }));
  }

  private applyPastedAddresses(addresses: ReadonlyArray<string>, startIndex: number): void {
    const maxPasteCount = Math.max(0, MAX_WALLET_ROWS - startIndex);
    const limitedAddresses = addresses.slice(0, maxPasteCount);

    limitedAddresses.forEach((address, offset) => {
      const rowIndex = startIndex + offset;
      while (this.walletFormArray.length <= rowIndex) {
        this.walletFormArray.push(this.createWalletFormGroup(this.walletFormArray.length));
      }

      const control = this.walletFormArray.at(rowIndex).controls.address;
      control.setValue(address);
      control.markAsDirty();
      control.markAsTouched();
    });
  }

  private resetForm(): void {
    while (this.walletFormArray.length > 1) {
      this.walletFormArray.removeAt(this.walletFormArray.length - 1);
    }

    const firstWalletForm = this.walletFormArray.controls[0];
    firstWalletForm.controls.address.setValue('');
    firstWalletForm.controls.label.setValue('Wallet 1');
    firstWalletForm.controls.color.setValue(WALLET_COLOR_PALETTE[0]);

    firstWalletForm.controls.address.markAsPristine();
    firstWalletForm.controls.address.markAsUntouched();
    firstWalletForm.controls.label.markAsPristine();
    firstWalletForm.controls.label.markAsUntouched();
    firstWalletForm.controls.color.markAsPristine();
    firstWalletForm.controls.color.markAsUntouched();
  }
}

function detectWalletKind(address: string): WalletKind | null {
  if (EVM_ADDRESS_PATTERN.test(address)) {
    return 'evm';
  }
  if (SOLANA_ADDRESS_PATTERN.test(address)) {
    return 'solana';
  }
  if (TON_FRIENDLY_ADDRESS_PATTERN.test(address)) {
    return 'ton';
  }
  return null;
}

function walletScopeId(address: string, kind: WalletKind): string {
  const trimmed = address.trim();
  return kind === 'evm' ? trimmed.toLowerCase() : trimmed;
}

function networksForWalletKind(
  kind: WalletKind | null,
  evmNetworks: ReadonlyArray<EvmNetworkId>
): ReadonlyArray<OnChainWalletNetworkId> {
  if (kind === 'solana') {
    return ['SOLANA'];
  }
  if (kind === 'ton') {
    return ['TON'];
  }
  return evmNetworks.filter((network) => network !== 'BYBIT') as ReadonlyArray<OnChainWalletNetworkId>;
}
