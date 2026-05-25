import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';

import { SessionIntegrationResponse } from '../../../core/models/wallet-api.models';

export type IntegrationStatusTone = 'ready' | 'busy' | 'error' | 'idle';

@Component({
  selector: 'wr-integrations-settings-section',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './integrations-settings-section.component.html',
  styleUrl: './integrations-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationsSettingsSectionComponent {
  @Input({ required: true }) bybitForm!: FormGroup;
  @Input() bybitIntegration: SessionIntegrationResponse | null = null;
  @Input({ required: true }) formatDateTime!: (value: string | null) => string;

  @Input() compact = false;
  @Input() showSecret = false;
  @Input() saving = false;

  @Input({ required: true }) statusTone!: (integration: SessionIntegrationResponse) => IntegrationStatusTone;

  @Output() readonly toggleSecret = new EventEmitter<void>();
  @Output() readonly reset = new EventEmitter<void>();
  @Output() readonly save = new EventEmitter<void>();
  @Output() readonly disconnect = new EventEmitter<void>();
}

