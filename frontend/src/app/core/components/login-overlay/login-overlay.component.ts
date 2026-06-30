import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'wr-login-overlay',
  standalone: true,
  templateUrl: './login-overlay.component.html',
  styleUrl: './login-overlay.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginOverlayComponent {
  readonly authService = inject(AuthService);

  signInWithGoogle(): void {
    this.authService.loginWithGoogle();
  }
}
