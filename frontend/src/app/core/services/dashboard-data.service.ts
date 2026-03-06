import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';

import { DASHBOARD_MOCK_DATA } from '../data/dashboard.mock';
import { DashboardData } from '../models/dashboard.models';

@Injectable({ providedIn: 'root' })
export class DashboardDataService {
  getDashboardData(): Observable<DashboardData> {
    return of(DASHBOARD_MOCK_DATA).pipe(delay(240));
  }
}
