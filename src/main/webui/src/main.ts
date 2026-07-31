import { loadTranslations } from '@angular/localize';
import { LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import { bootstrapApplication } from '@angular/platform-browser';
import localeFr from '@angular/common/locales/fr';
import localeEn from '@angular/common/locales/en';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { getStoredLocale } from './app/core/locale';

registerLocaleData(localeFr, 'fr');
registerLocaleData(localeEn, 'en');

async function bootstrap(): Promise<void> {
  const locale = getStoredLocale();
  if (locale === 'en') {
    const response = await fetch('i18n/messages.en.json');
    const translations = (await response.json()) as Record<string, string>;
    loadTranslations(translations);
  }
  await bootstrapApplication(App, {
    ...appConfig,
    providers: [...appConfig.providers, { provide: LOCALE_ID, useValue: locale }]
  });
}

bootstrap().catch((err) => console.error(err));
