import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'sessions/:sessionId/assets/:familyIdentity', component: DashboardComponent },
  { path: '**', redirectTo: '' },
];
