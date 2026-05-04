import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'settings', component: DashboardComponent, data: { mode: 'settings' } },
  { path: 'lending', component: DashboardComponent, data: { mode: 'lending' } },
  { path: 'sessions/:sessionId/assets/:familyIdentity', component: DashboardComponent },
  { path: '**', redirectTo: '' },
];
