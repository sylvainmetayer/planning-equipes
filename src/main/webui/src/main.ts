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
  // Falls back to the source language rather than to a blank page: the stored
  // locale survives a reload, so a catalog that cannot be fetched (offline,
  // bad deploy, 404) would otherwise leave the app permanently unbootable.
  const locale = (await loadEnglishTranslations()) ? 'en' : 'fr';
  await bootstrapApplication(App, {
    ...appConfig,
    providers: [...appConfig.providers, { provide: LOCALE_ID, useValue: locale }]
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
