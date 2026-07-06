import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';

import { SessionIntegrationResponse } from '../../../core/models/wallet-api.models';

export type IntegrationStatusTone = 'ready' | 'busy' | 'error' | 'idle';

interface AvailableProvider {
  id: string;
  label: string;
  abbr: string;
  soon: boolean;
}

export const AVAILABLE_PROVIDERS: AvailableProvider[] = [
  { id: 'BYBIT', label: 'Bybit', abbr: 'BY', soon: false },
  { id: 'DZENGI', label: 'Dzengi', abbr: 'DZ', soon: true },
  { id: 'BINANCE', label: 'Binance', abbr: 'BN', soon: true },
  { id: 'OKX', label: 'OKX', abbr: 'OK', soon: true },
];

@Component({
  selector: 'wr-integrations-settings-section',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './integrations-settings-section.component.html',
  styleUrl: './integrations-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationsSettingsSectionComponent {
  /** All connected integrations to display as compact rows */
  @Input({ required: true }) integrations: ReadonlyArray<SessionIntegrationResponse> = [];
  /** Form for the connect/edit flow */
  @Input({ required: true }) bybitForm!: FormGroup;
  @Input({ required: true }) formatDateTime!: (value: string | null) => string;

  @Input() compact = false;
  @Input() showSecret = false;
  @Input() saving = false;

  @Input({ required: true }) statusTone!: (integration: SessionIntegrationResponse) => IntegrationStatusTone;

  @Output() readonly toggleSecret = new EventEmitter<void>();
  @Output() readonly reset = new EventEmitter<void>();
  @Output() readonly save = new EventEmitter<void>();
  /** Emits integrationId to disconnect */
  @Output() readonly disconnect = new EventEmitter<string>();

  readonly availableProviders = AVAILABLE_PROVIDERS;

  /** Which integration row is currently expanded for editing */
  readonly expandedId = signal<string | null>(null);
  /** Which integration is pending disconnect confirmation */
  readonly confirmDisconnectId = signal<string | null>(null);
  /** Whether the "Connect integration" form is open */
  readonly showConnectForm = signal(false);
  /** Provider selected in the add form */
  readonly selectedProvider = signal<string | null>('BYBIT');

  colorOf(integration: SessionIntegrationResponse): string {
    return integration.color ?? '#f7a600';
  }

  toggleExpanded(integrationId: string): void {
    const current = this.expandedId();
    if (current === integrationId) {
      this.expandedId.set(null);
    } else {
      this.expandedId.set(integrationId);
      this.confirmDisconnectId.set(null);
    }
  }

  requestDisconnect(integrationId: string): void {
    this.confirmDisconnectId.set(integrationId);
    this.expandedId.set(null);
  }

  cancelDisconnect(): void {
    this.confirmDisconnectId.set(null);
  }

  confirmDisconnect(integrationId: string): void {
    this.confirmDisconnectId.set(null);
    this.disconnect.emit(integrationId);
  }

  openConnectForm(): void {
    this.showConnectForm.set(true);
    this.selectedProvider.set('BYBIT');
    this.expandedId.set(null);
    this.confirmDisconnectId.set(null);
  }

  closeConnectForm(): void {
    this.showConnectForm.set(false);
    this.reset.emit();
  }

  selectProvider(providerId: string): void {
    const provider = this.availableProviders.find((p) => p.id === providerId);
    if (!provider || provider.soon) return;
    this.selectedProvider.set(providerId);
  }

  providerAbbr(provider: string | null): string {
    return (provider ?? 'INT').slice(0, 2).toUpperCase();
  }
}
