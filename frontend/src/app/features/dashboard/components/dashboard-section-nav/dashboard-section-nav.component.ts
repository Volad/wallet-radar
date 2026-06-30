import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

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
  private sanitizer = inject(DomSanitizer);

  @Input({ required: true }) sections: ReadonlyArray<SectionMeta> = [];
  @Input({ required: true }) activeSection: DashboardSection = 'tokens';
  @Input() isSettingsActive = false;

  @Output() sectionChange = new EventEmitter<DashboardSection>();
  @Output() settingsSelect = new EventEmitter<void>();

  selectSection(section: SectionMeta): void {
    if (section.soon) {
      return;
    }
    this.sectionChange.emit(section.id);
  }

  openSettings(): void {
    this.settingsSelect.emit();
  }

  getSectionIcon(sectionId: DashboardSection): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(NAV_ICONS[sectionId] ?? NAV_ICONS_SETTINGS);
  }

  getSettingsIcon(): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(NAV_ICONS_SETTINGS);
  }
}

const SVG_ATTRS = `xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"`;

const NAV_ICONS: Record<DashboardSection, string> = {
  // Dashboard: monitor + bar chart
  tokens: `<svg ${SVG_ATTRS}>
    <rect x="1" y="1.5" width="18" height="13" rx="1.5"/>
    <path d="M7.5 14.5v3M12.5 14.5v3M5 17.5h10"/>
    <path d="M4.5 10.5V8M7.5 10.5V6.5M10.5 10.5V8.5M13.5 10.5V5.5M16 10.5V7"/>
    <path d="M3 10.5h14"/>
  </svg>`,

  // LP: ladder into pool with coins
  lp: `<svg ${SVG_ATTRS}>
    <path d="M7 1.5v9M13 1.5v9"/>
    <path d="M7 5h6M7 8h6"/>
    <path d="M1.5 13q2.5-2.5 5 0t5 0 5 0"/>
    <ellipse cx="6.5" cy="17.5" rx="2.5" ry="1.2"/>
    <ellipse cx="13.5" cy="17.5" rx="2.5" ry="1.2"/>
  </svg>`,

  // Lending: two persons + coin + arrows (P2P lending)
  lending: `<svg ${SVG_ATTRS}>
    <circle cx="15.5" cy="3.5" r="1.5"/>
    <path d="M13 7c0-1.4 1.1-2.5 2.5-2.5S18 5.6 18 7"/>
    <circle cx="4.5" cy="16.5" r="1.5"/>
    <path d="M2 20c0-1.4 1.1-2.5 2.5-2.5S7 18.6 7 20"/>
    <circle cx="10" cy="10" r="2.5"/>
    <path d="M10 8.3v3.4"/>
    <path d="M13 7.5L11.2 9.2M11.8 9.2l-.6-.6.6-.6"/>
    <path d="M7 12.5L8.8 10.8M8.2 10.8l.6.6-.6.6"/>
  </svg>`,
};

const NAV_ICONS_SETTINGS = `<svg ${SVG_ATTRS}>
  <circle cx="10" cy="10" r="2.5"/>
  <path d="M10 1.5v2M10 16.5v2M1.5 10h2M16.5 10h2"/>
  <path d="M3.8 3.8l1.4 1.4M14.8 14.8l1.4 1.4M16.2 3.8l-1.4 1.4M5.2 14.8l-1.4 1.4"/>
</svg>`;
