import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';

import { SessionIntegrationResponse } from '../../../core/models/wallet-api.models';

export type IntegrationStatusTone = 'ready' | 'busy' | 'error' | 'idle';

export interface IntegrationProviderMeta {
  readonly id: string;
  readonly label: string;
  readonly abbr: string;
  readonly soon: boolean;
  readonly defaultDisplayName: string;
  readonly instructions: ReadonlyArray<string>;
  readonly apiManagementUrl?: string;
  readonly apiManagementLabel?: string;
}

export const AVAILABLE_PROVIDERS: ReadonlyArray<IntegrationProviderMeta> = [
  {
    id: 'BYBIT',
    label: 'Bybit',
    abbr: 'BY',
    soon: false,
    defaultDisplayName: 'Bybit',
    instructions: [
      'Log in to Bybit and open Account → API Management.',
      'Create a new API key with read-only permissions.',
      'Copy API key and secret. The secret is shown only once.',
      'Paste both values below and save.',
    ],
    apiManagementUrl: 'https://www.bybit.com/app/user/api-management',
    apiManagementLabel: 'Open Bybit API Management',
  },
  {
    id: 'DZENGI',
    label: 'Dzengi',
    abbr: 'DZ',
    soon: false,
    defaultDisplayName: 'Dzengi',
    instructions: [
      'Log in to Dzengi and open API Management in account settings.',
      'Create a new API key with read-only permissions.',
      'Copy API key and secret. The secret is shown only once.',
      'Paste both values below and save.',
    ],
    apiManagementUrl: 'https://dzengi.com',
    apiManagementLabel: 'Open Dzengi',
  },
  {
    id: 'BINANCE',
    label: 'Binance',
    abbr: 'BN',
    soon: true,
    defaultDisplayName: 'Binance',
    instructions: [],
  },
  {
    id: 'OKX',
    label: 'OKX',
    abbr: 'OK',
    soon: true,
    defaultDisplayName: 'OKX',
    instructions: [],
  },
];

const PROVIDER_META_BY_ID = new Map(AVAILABLE_PROVIDERS.map((provider) => [provider.id, provider]));

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
  @Input({ required: true }) integrationForm!: FormGroup;
  @Input({ required: true }) formatDateTime!: (value: string | null) => string;

  @Input() compact = false;
  @Input() showSecret = false;
  @Input() saving = false;
  @Input() testingConnection = false;
  @Input() testConnectionMessage: string | null = null;

  @Input({ required: true }) statusTone!: (integration: SessionIntegrationResponse) => IntegrationStatusTone;

  @Output() readonly toggleSecret = new EventEmitter<void>();
  @Output() readonly reset = new EventEmitter<void>();
  @Output() readonly save = new EventEmitter<string>();
  @Output() readonly testConnection = new EventEmitter<string>();
  /** Emits provider id when connect/edit context changes */
  @Output() readonly providerSelected = new EventEmitter<string>();
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
    if (integration.color) {
      return integration.color;
    }
    const provider = integration.provider?.toUpperCase();
    if (provider === 'DZENGI') {
      return '#22d3ee';
    }
    if (provider === 'BYBIT') {
      return '#f7a600';
    }
    return '#e2e8f0';
  }

  providerMeta(provider: string | null | undefined): IntegrationProviderMeta | undefined {
    if (!provider) {
      return undefined;
    }
    return PROVIDER_META_BY_ID.get(provider.toUpperCase());
  }

  isProviderEnabled(providerId: string): boolean {
    const provider = this.providerMeta(providerId);
    return provider !== undefined && !provider.soon;
  }

  toggleExpanded(integrationId: string): void {
    const current = this.expandedId();
    if (current === integrationId) {
      this.expandedId.set(null);
    } else {
      this.expandedId.set(integrationId);
      this.confirmDisconnectId.set(null);
      const integration = this.integrations.find((candidate) => candidate.integrationId === integrationId);
      if (integration?.provider) {
        this.providerSelected.emit(integration.provider);
      }
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
    this.providerSelected.emit('BYBIT');
  }

  closeConnectForm(): void {
    this.showConnectForm.set(false);
    this.reset.emit();
  }

  selectProvider(providerId: string): void {
    const provider = this.availableProviders.find((candidate) => candidate.id === providerId);
    if (!provider || provider.soon) {
      return;
    }
    this.selectedProvider.set(providerId);
    this.providerSelected.emit(providerId);
  }

  providerAbbr(provider: string | null): string {
    return this.providerMeta(provider)?.abbr ?? (provider ?? 'INT').slice(0, 2).toUpperCase();
  }

  emitSave(provider: string | null | undefined): void {
    if (!provider) {
      return;
    }
    this.save.emit(provider);
  }

  emitTestConnection(provider: string | null | undefined): void {
    if (!provider) {
      return;
    }
    this.testConnection.emit(provider);
  }
}
