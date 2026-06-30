import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { AuthService } from './core/services/auth.service';
import { LoginOverlayComponent } from './core/components/login-overlay/login-overlay.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LoginOverlayComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  readonly authService = inject(AuthService);

  /** True once auth check completes AND user is not authenticated. */
  readonly showLoginOverlay = computed(
    () => this.authService.isAuthChecked() && !this.authService.authState().authenticated
  );

  constructor() {
    this.authService.checkAuth().subscribe();
  }
}
