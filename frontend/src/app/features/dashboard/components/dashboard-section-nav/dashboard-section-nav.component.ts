import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';

import { DashboardSection, SectionMeta } from '../../../../core/models/dashboard.models';

@Component({
  selector: 'wr-dashboard-section-nav',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard-section-nav.component.html',
  styleUrl: './dashboard-section-nav.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardSectionNavComponent {
  @Input({ required: true }) sections: ReadonlyArray<SectionMeta> = [];
  @Input({ required: true }) activeSection: DashboardSection = 'tokens';

  @Output() sectionChange = new EventEmitter<DashboardSection>();

  selectSection(section: SectionMeta): void {
    if (section.soon) {
      return;
    }
    this.sectionChange.emit(section.id);
  }

  getSectionIcon(sectionId: DashboardSection): string {
    switch (sectionId) {
      case 'tokens':
        return '◍';
      case 'lp':
        return '◢';
      case 'lending':
        return '⌂';
      case 'staking':
        return '⚡';
      default:
        return '•';
    }
  }
}
