import { loadTranslations } from '@angular/localize';
import { LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import { bootstrapApplication } from '@angular/platform-browser';
import localeFr from '@angular/common/locales/fr';
import localeEn from '@angular/common/locales/en';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { getStoredLocale } from './app/core/locale';
import { APP_CONFIG } from './app/core/app-config';
import { BRANDING, appliquerBranding, loadBranding } from './app/core/branding';
import { initObservability, loadAppConfig } from './app/core/observability';

registerLocaleData(localeFr, 'fr');
registerLocaleData(localeEn, 'en');

async function bootstrap(): Promise<void> {
  // Falls back to the source language rather than to a blank page: the stored
  // locale survives a reload, so a catalog that cannot be fetched (offline,
  // bad deploy, 404) would otherwise leave the app permanently unbootable.
  const [isEnglish, appConfig_, branding] = await Promise.all([
    loadEnglishTranslations(),
    loadAppConfig(),
    loadBranding()
  ]);
  // Before the first frame: the tab must never flash a placeholder name, and
  // the accent colour must be in place before any component paints.
  appliquerBranding(branding);
  // Awaited: when a DSN is configured, this is where the Sentry SDK is
  // fetched. Errors thrown during bootstrap are worth catching too.
  const observability = await initObservability(appConfig_);
  const locale = isEnglish ? 'en' : 'fr';
  // `index.html` can only declare one language; the UI picks its own at
  // runtime. Without this, a screen reader reads the English catalog with
  // French pronunciation rules (WCAG 3.1.1).
  document.documentElement.lang = locale;
  await bootstrapApplication(App, {
    ...appConfig,
    providers: [
      ...appConfig.providers,
      ...observability,
      // Fetched once before bootstrap: every screen reads the same answer
      // instead of asking the server again.
      { provide: APP_CONFIG, useValue: appConfig_ },
      { provide: BRANDING, useValue: branding },
      { provide: LOCALE_ID, useValue: locale }
    ]
  });
}

/** True once the English catalog is loaded; false when French must be used. */
async function loadEnglishTranslations(): Promise<boolean> {
  if (getStoredLocale() !== 'en') {
    return false;
  }
  try {
    const response = await fetch('i18n/messages.en.json');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    loadTranslations((await response.json()) as Record<string, string>);
    return true;
  } catch (error) {
    console.error('Could not load the English translations, falling back to French.', error);
    return false;
  }
}

bootstrap().catch((err) => console.error(err));
