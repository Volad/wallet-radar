import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'wr-account-settings-section',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './account-settings-section.component.html',
  styleUrl: './account-settings-section.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountSettingsSectionComponent {
  readonly authService = inject(AuthService);
  readonly signingOut = signal(false);

  signInWithGoogle(): void {
    this.authService.loginWithGoogle();
  }

  signOut(): void {
    this.signingOut.set(true);
    this.authService.logout().subscribe({
      next: () => this.signingOut.set(false),
      error: () => this.signingOut.set(false),
    });
  }
}
