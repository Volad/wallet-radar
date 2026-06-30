import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Attaches {@code withCredentials: true} to every API request so the
 * {@code wr_auth} HttpOnly cookie is sent automatically.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const withCreds = req.clone({ withCredentials: true });
  return next(withCreds);
};
