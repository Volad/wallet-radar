import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'wr-accounting-settings-section',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './accounting-settings-section.component.html',
  styleUrl: './accounting-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountingSettingsSectionComponent {}

