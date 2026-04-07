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
import { AddSessionRequestItem, EvmNetworkId } from '../../../../core/models/wallet-api.models';

export type WalletSubmitState = 'idle' | 'submitting' | 'success' | 'error';

export interface EvmNetworkPresentation {
  readonly id: EvmNetworkId;
  readonly icon: string;
  readonly label: string;
  readonly color: string;
}

type WalletAddressState = 'empty' | 'ok' | 'warn' | 'error';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;

interface WalletAddressEvaluation {
  readonly state: WalletAddressState;
  readonly message: string | null;
}

const EVM_ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/iu;
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
    return summary.ready > 0 && this.supportedEvmNetworks.length > 0 && !this.isSubmitBusy;
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
      address: this.formBuilder.control('', {
        validators: [Validators.pattern(EVM_ADDRESS_PATTERN)],
      }),
      label: this.formBuilder.control(`Wallet ${index + 1}`),
      color: this.formBuilder.control(WALLET_COLOR_PALETTE[index % WALLET_COLOR_PALETTE.length]),
    });
  }

  private evaluateWalletAddress(control: FormControl<string>, index: number): WalletAddressEvaluation {
    const address = control.value.trim();
    if (address.length === 0) {
      return { state: 'empty', message: null };
    }

    if (!EVM_ADDRESS_PATTERN.test(address)) {
      return { state: 'error', message: 'Invalid EVM address' };
    }

    const lowerCaseAddress = address.toLowerCase();
    const isDuplicateInInput = this.walletFormArray.controls.some((walletGroup, currentIndex) => {
      if (currentIndex === index) {
        return false;
      }
      return walletGroup.controls.address.value.trim().toLowerCase() === lowerCaseAddress;
    });
    if (isDuplicateInInput) {
      return { state: 'error', message: 'Duplicate address in current list' };
    }

    const isExistingWallet = this.existingWallets.some(
      (wallet) => wallet.address.trim().toLowerCase() === lowerCaseAddress
    );
    if (isExistingWallet) {
      return {
        state: 'warn',
        message: 'Already tracked — new networks will be added',
      };
    }

    return { state: 'ok', message: null };
  }

  private buildWalletItems(): ReadonlyArray<AddSessionRequestItem> {
    return this.walletFormArray.controls
      .map((walletForm, index) => {
        const evaluation = this.evaluateWalletAddress(walletForm.controls.address, index);
        return {
          state: evaluation.state,
          address: walletForm.controls.address.value.trim(),
          label: walletForm.controls.label.value.trim() || `Wallet ${index + 1}`,
          color: walletForm.controls.color.value,
          networks: [...this.supportedEvmNetworks] as ReadonlyArray<EvmNetworkId>,
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
