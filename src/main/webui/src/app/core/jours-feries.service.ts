// The French public holidays, as the entry screens mark them. The server is
// the one source: the frontend never computes Easter (`core/horaire-stand.ts`
// is the only place the client reimplements domain, and it stays so). Read
// one calendar year at a time and kept for the session — a calendar fact
// does not change under a page.

import { Injectable, inject, signal } from '@angular/core';
import { JoursFeriesApi } from './api/jours-feries-api';

/** `AAAA-MM-JJ` → its year, `null` for anything the date field may hold half-typed. */
function yearOf(date: string | null | undefined): number | null {
  const match = date ? /^(\d{4})-\d{2}-\d{2}$/.exec(date) : null;
  return match ? Number(match[1]) : null;
}

@Injectable({ providedIn: 'root' })
export class JoursFeriesService {
  private readonly api = inject(JoursFeriesApi);

  /** Date → the holiday's name, for every year loaded so far. */
  private readonly _byDate = signal<ReadonlyMap<string, string>>(new Map());
  readonly byDate = this._byDate.asReadonly();

  /** One request per year, shared by every caller asking while it runs. */
  private readonly annees = new Map<number, Promise<void>>();

  /**
   * Loads the holidays of every year the dates fall in, each year once. A
   * failed read is forgotten, so the next call asks again; the marking is
   * informative and a page never waits on it.
   */
  load(dates: Iterable<string | null | undefined>): Promise<void> {
    const years = new Set<number>();
    for (const date of dates) {
      const year = yearOf(date);
      if (year !== null) {
        years.add(year);
      }
    }
    return Promise.all(Array.from(years, (year) => this.loadYear(year))).then(() => undefined);
  }

  /** The holiday's name on `date`, `null` on an ordinary day or a year not loaded. */
  label(date: string | null | undefined): string | null {
    return date ? (this.byDate().get(date) ?? null) : null;
  }

  private loadYear(year: number): Promise<void> {
    const pending = this.annees.get(year);
    if (pending) {
      return pending;
    }
    const request = this.api
      .between(`${year}-01-01`, `${year}-12-31`)
      .then((feries) =>
        this._byDate.update((courant) => {
          const suivant = new Map(courant);
          for (const ferie of feries) {
            suivant.set(ferie.date, ferie.label);
          }
          return suivant;
        }),
      )
      .catch(() => {
        this.annees.delete(year);
      });
    this.annees.set(year, request);
    return request;
  }
}
