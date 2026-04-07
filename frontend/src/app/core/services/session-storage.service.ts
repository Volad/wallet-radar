import { Injectable } from '@angular/core';

const SESSION_ID_STORAGE_KEY = 'wr.sessionId';
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

@Injectable({ providedIn: 'root' })
export class SessionStorageService {
  getSessionId(): string | null {
    if (!this.hasStorage()) {
      return null;
    }

    const raw = localStorage.getItem(SESSION_ID_STORAGE_KEY);
    if (raw === null) {
      return null;
    }

    const normalized = raw.trim();
    if (!UUID_PATTERN.test(normalized)) {
      this.clearSessionId();
      return null;
    }

    return normalized;
  }

  setSessionId(sessionId: string): void {
    if (!this.hasStorage()) {
      return;
    }

    const normalized = sessionId.trim();
    if (!UUID_PATTERN.test(normalized)) {
      return;
    }

    localStorage.setItem(SESSION_ID_STORAGE_KEY, normalized);
  }

  clearSessionId(): void {
    if (!this.hasStorage()) {
      return;
    }

    localStorage.removeItem(SESSION_ID_STORAGE_KEY);
  }

  private hasStorage(): boolean {
    return typeof localStorage !== 'undefined';
  }
}
