import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { legacyAssetLedgerRedirectGuard } from './core/guards/legacy-asset-ledger-redirect.guard';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'settings', component: DashboardComponent, data: { mode: 'settings' } },
  { path: 'lending', component: DashboardComponent, data: { mode: 'lending' } },
  { path: 'lp', component: DashboardComponent, data: { mode: 'lp' } },
  { path: 'move-basis/:familyIdentity', component: DashboardComponent, data: { mode: 'move-basis' } },
  {
    path: 'sessions/:sessionId/assets/:familyIdentity',
    canActivate: [legacyAssetLedgerRedirectGuard],
    component: DashboardComponent,
  },
  { path: '**', redirectTo: '' },
];
