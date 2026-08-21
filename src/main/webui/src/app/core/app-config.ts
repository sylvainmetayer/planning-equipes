import { InjectionToken } from '@angular/core';
import { AppConfig } from './models';

/**
 * The `/api/config` answer, fetched once in `main.ts` before the application
 * bootstraps and provided here so any screen can read it synchronously.
 *
 * <p>It exists because some of that configuration decides what to *render* —
 * the Quarkus Dev UI link only makes sense against a server launched in dev
 * mode — and a component cannot wait for an HTTP round trip to know whether a
 * menu entry belongs on screen.</p>
 */
export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');
