import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { groupeInterceptor } from './core/groupe.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Every backend call carries the edition this browser works in — see
    // core/groupe.interceptor.ts and docs/groupes.md §5.
    provideHttpClient(withFetch(), withInterceptors([groupeInterceptor])),
    provideRouter(routes)
  ]
};
