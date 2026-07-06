import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';

@Component({
  selector: 'wr-general-settings-section',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './general-settings-section.component.html',
  styleUrl: './general-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeneralSettingsSectionComponent {
  @Input({ required: true }) form!: FormGroup;
  @Input() saving = false;
  @Output() readonly save = new EventEmitter<void>();
}

