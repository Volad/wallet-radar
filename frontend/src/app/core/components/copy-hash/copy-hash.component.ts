import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  Input,
  OnDestroy,
  signal,
} from '@angular/core';

@Component({
  selector: 'wr-copy-hash',
  standalone: true,
  templateUrl: './copy-hash.component.html',
  styleUrl: './copy-hash.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CopyHashComponent implements OnDestroy {
  @Input({ required: true }) hash: string | null = null;

  readonly copied = signal(false);
  private resetTimerId: number | null = null;

  private readonly destroyRef = inject(DestroyRef);

  ngOnDestroy(): void {
    if (this.resetTimerId !== null) {
      window.clearTimeout(this.resetTimerId);
    }
  }

  get short(): string {
    const h = this.hash;
    if (!h || h.length <= 14) return h ?? '';
    return `${h.slice(0, 8)}...${h.slice(-6)}`;
  }

  get title(): string {
    if (!this.hash) return '';
    return this.copied() ? 'Copied' : `Copy ${this.hash}`;
  }

  async onClick(event: Event): Promise<void> {
    event.stopPropagation();
    const value = this.hash;
    if (!value) return;
    try {
      if ('clipboard' in navigator && navigator.clipboard !== undefined) {
        await navigator.clipboard.writeText(value);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = value;
        textarea.setAttribute('readonly', 'true');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      this.copied.set(true);
      if (this.resetTimerId !== null) {
        window.clearTimeout(this.resetTimerId);
      }
      this.resetTimerId = window.setTimeout(() => {
        this.copied.set(false);
        this.resetTimerId = null;
      }, 1400);
    } catch {
      this.copied.set(false);
    }
  }
}
