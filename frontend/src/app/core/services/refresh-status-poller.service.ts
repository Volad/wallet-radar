import { DestroyRef, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Observable, Subscription, expand, switchMap, timer } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { RefreshStatusResponse } from '../models/wallet-api.models';

const ACTIVE_POLL_MS = 3_000;
const KEEPALIVE_POLL_MS = 25_000;

export interface RefreshStatusPollCallbacks {
  readonly onStatus: (status: RefreshStatusResponse, previous: RefreshStatusResponse | null) => void;
  readonly onError?: () => void;
}

@Injectable({ providedIn: 'root' })
export class RefreshStatusPollerService {
  startAdaptivePolling(
    fetchStatus: () => Observable<RefreshStatusResponse>,
    callbacks: RefreshStatusPollCallbacks,
    destroyRef: DestroyRef
  ): Subscription {
    let previous: RefreshStatusResponse | null = null;

    const pollOnce = (): Observable<RefreshStatusResponse> =>
      fetchStatus().pipe(
        catchError(() => {
          callbacks.onError?.();
          if (previous !== null) {
            return [previous];
          }
          return EMPTY;
        })
      );

    return pollOnce().pipe(
      expand((status) => {
        callbacks.onStatus(status, previous);
        previous = status;
        const delayMs = status.anyActive ? ACTIVE_POLL_MS : KEEPALIVE_POLL_MS;
        return timer(delayMs).pipe(switchMap(() => pollOnce()));
      }),
      takeUntilDestroyed(destroyRef)
    ).subscribe();
  }
}
