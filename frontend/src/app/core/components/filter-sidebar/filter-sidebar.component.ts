import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  HostBinding,
  Input,
  OnInit,
  Output,
  signal,
} from '@angular/core';

@Component({
  selector: 'wr-filter-sidebar',
  standalone: true,
  templateUrl: './filter-sidebar.component.html',
  styleUrl: './filter-sidebar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FilterSidebarComponent implements OnInit {
  /** Short page label shown next to collapse arrow, e.g. "LP" or "Lending". */
  @Input() title = 'Filters';

  /** One-liner hint rendered below the toggle (e.g. "Wallets & networks"). */
  @Input() subtitle: string | null = null;

  /** Number of active (non-default) filters — drives the badge and "Clear all" visibility. */
  @Input() activeCount = 0;

  /** Whether the sidebar starts collapsed. */
  @Input() startCollapsed = false;

  /** Emitted when the user clicks "Clear all". */
  @Output() readonly clearFilters = new EventEmitter<void>();

  readonly isCollapsed = signal(false);

  @HostBinding('class.collapsed') get collapsedHost(): boolean {
    return this.isCollapsed();
  }

  ngOnInit(): void {
    if (this.startCollapsed) {
      this.isCollapsed.set(true);
    }
  }

  toggle(): void {
    this.isCollapsed.update(v => !v);
  }
}
