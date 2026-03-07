import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';

import { PortfolioMetric, WalletInfo } from '../../../../core/models/dashboard.models';

@Component({
  selector: 'wr-dashboard-topbar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard-topbar.component.html',
  styleUrl: './dashboard-topbar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardTopbarComponent {
  @Input({ required: true }) metrics: ReadonlyArray<PortfolioMetric> = [];
  @Input({ required: true }) wallets: ReadonlyArray<WalletInfo> = [];
  @Input({ required: true }) isBackfillVisible = false;
  @Input({ required: true }) backfillProgressPct = 0;
  @Input({ required: true }) backfillNetworksLabel = '';

  @Output() addWallet = new EventEmitter<void>();

  shortAddress(address: string): string {
    if (address.length < 12) {
      return address;
    }
    return `${address.slice(0, 6)}...${address.slice(-4)}`;
  }
}
