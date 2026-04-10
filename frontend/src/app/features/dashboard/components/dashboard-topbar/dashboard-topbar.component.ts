import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';

import { IntegrationInfo, PortfolioMetric, WalletInfo } from '../../../../core/models/dashboard.models';

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
  @Input() integrations: ReadonlyArray<IntegrationInfo> = [];
  @Input({ required: true }) isBackfillVisible = false;
  @Input({ required: true }) backfillProgressPct = 0;
  @Input({ required: true }) statusLabel = '';
  @Input({ required: true }) statusSubline = '';
  @Input({ required: true }) showProgress = true;
  @Input() showRefresh = false;
  @Input() canRefresh = false;
  @Input() isRefreshing = false;
  @Input() refreshMessage = '';

  @Output() addWallet = new EventEmitter<void>();
  @Output() refresh = new EventEmitter<void>();

  shortAddress(address: string): string {
    if (address.length < 12) {
      return address;
    }
    return `${address.slice(0, 6)}...${address.slice(-4)}`;
  }

  shortIntegrationRef(accountRef: string): string {
    const trimmed = accountRef.trim();
    const colonIndex = trimmed.indexOf(':');
    if (colonIndex >= 0 && colonIndex < trimmed.length - 1) {
      return trimmed.slice(colonIndex + 1);
    }
    return this.shortAddress(trimmed);
  }

  onRefresh(): void {
    if (!this.canRefresh || this.isRefreshing) {
      return;
    }
    this.refresh.emit();
  }
}
