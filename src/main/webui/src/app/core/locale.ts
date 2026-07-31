export type AppLocale = 'fr' | 'en';

const STORAGE_KEY = 'planning-equipes.locale';

export function getStoredLocale(): AppLocale {
  return localStorage.getItem(STORAGE_KEY) === 'en' ? 'en' : 'fr';
}

export function setStoredLocaleAndReload(locale: AppLocale): void {
  localStorage.setItem(STORAGE_KEY, locale);
  location.reload();
}

/** Intl tag for the current UI locale, used for date/time formatting. */
export function intlLocale(): string {
  return getStoredLocale() === 'en' ? 'en-US' : 'fr-FR';
}
