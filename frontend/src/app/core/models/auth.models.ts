export interface AuthMeResponse {
  readonly authenticated: boolean;
  readonly provider?: string;
  readonly email?: string;
  readonly displayName?: string;
  readonly pictureUrl?: string;
  readonly sessionId?: string;
}
