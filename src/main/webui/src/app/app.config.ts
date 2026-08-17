import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { editionInterceptor } from './core/edition.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Every backend call carries the edition this browser works in — see
    // core/edition.interceptor.ts and docs/editions.md §5.
    provideHttpClient(withFetch(), withInterceptors([editionInterceptor])),
    provideRouter(routes)
  ]
};
