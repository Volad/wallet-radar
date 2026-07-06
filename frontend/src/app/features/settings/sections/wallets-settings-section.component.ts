import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';

import { EvmNetworkId } from '../../../core/models/wallet-api.models';

export interface SettingsWalletDraftView {
  readonly id: string;
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<EvmNetworkId>;
  readonly networksOpen: boolean;
}

export interface NetworkPresentationItem {
  readonly id: EvmNetworkId;
  readonly label: string;
  readonly icon: string;
  readonly color: string;
}

@Component({
  selector: 'wr-wallets-settings-section',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './wallets-settings-section.component.html',
  styleUrl: './wallets-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletsSettingsSectionComponent {
  @Input() embeddedInDataSources = false;
  @Input({ required: true }) maxWallets!: number;
  @Input({ required: true }) walletsDraft!: ReadonlyArray<SettingsWalletDraftView>;
  @Input({ required: true }) pendingWallets!: ReadonlyArray<SettingsWalletDraftView>;
  @Input({ required: true }) networksPresentation!: ReadonlyArray<NetworkPresentationItem>;

  @Input({ required: true }) hasSavedWallets!: boolean;
  @Input({ required: true }) isWalletEditing!: boolean;
  @Input({ required: true }) walletListDirty!: boolean;
  @Input({ required: true }) canAddMoreWallets!: boolean;
  @Input({ required: true }) canSaveWallets!: boolean;
  @Input({ required: true }) saving!: boolean;

  @Input({ required: true }) validPendingWalletCount!: number;
  @Input({ required: true }) hasInvalidPendingWallets!: boolean;

  @Input({ required: true }) walletAddressError!: (walletId: string) => string | null;

  @Output() readonly addWallet = new EventEmitter<void>();
  @Output() readonly toggleSavedNetworks = new EventEmitter<string>();
  @Output() readonly togglePendingNetworks = new EventEmitter<string>();
  @Output() readonly removeWallet = new EventEmitter<string>();
  @Output() readonly updatePendingWalletField = new EventEmitter<{ walletId: string; field: 'address' | 'label'; value: string }>();
  @Output() readonly discardPending = new EventEmitter<void>();
  @Output() readonly saveWallets = new EventEmitter<void>();
}

