// The event-day screen (issue #297): mark somebody absent, look for a
// replacement, hand the seat over.
//
// Every call here names identifiers only. The repair assistant's own endpoint
// (`/api/postes/{id}/suggestions-reparation`) takes the whole planning as its
// body, which is fine for a desktop page that already holds it and wrong for
// this one: it runs on a phone in an aisle. `/api/jour-j/...` reads the
// persisted plan server-side instead — same service call underneath, same
// bounded cost, one identifier on the wire.
//
// Applying a replacement deliberately reuses the assistant's existing write
// (`AffectationExplanationService.appliquerReparation`), which is the surgical
// UPDATE that starts no solve. There is no second write path.

import { Injectable, inject } from '@angular/core';
import { AbsenceMarquee, ApercuPublication, EtatJourJ, SuggestionsReparation } from './models';
import { ApiService } from './api.service';

@Injectable({ providedIn: 'root' })
export class JourJService {
  private readonly api = inject(ApiService);

  /**
   * The whole screen in one answer. `date`/`heure` default server-side to today
   * and now: the reference moment comes back in the answer so the page states
   * the *server's* clock rather than the phone's.
   */
  etat(date?: string, heure?: string): Promise<EtatJourJ> {
    return this.api.get<EtatJourJ>(`/api/jour-j${query({ date, heure })}`);
  }

  /** Forced unavailability on every remaining timeslot, and the seats it frees. */
  marquerAbsent(
    animateurId: string,
    raison: string,
    date?: string,
    heure?: string,
  ): Promise<AbsenceMarquee> {
    return this.api.post<AbsenceMarquee>(`/api/jour-j/absences${query({ date, heure })}`, {
      animateurId,
      raison: raison.trim() ? raison.trim() : null,
    });
  }

  /** Undoes an absence: one timeslot when `creneauId` is given, the whole day otherwise. */
  annulerAbsence(animateurId: string, date?: string, creneauId?: number): Promise<void> {
    const url = `/api/jour-j/absences/${encodeURIComponent(animateurId)}${query({
      date,
      creneauId: creneauId === undefined ? undefined : String(creneauId),
    })}`;
    return this.api.delete(url);
  }

  /** Viable replacements for one seat, best impact first. */
  suggestions(posteId: string): Promise<SuggestionsReparation> {
    return this.api.post<SuggestionsReparation>(
      `/api/jour-j/postes/${encodeURIComponent(posteId)}/suggestions`,
      null,
    );
  }

  /**
   * How many people the next publication would write to — the count behind the
   * "changements non publiés" banner. Read from the existing publication
   * preview: this screen shows the number and links to the page that owns the
   * button, it never sends anything itself.
   */
  apercuPublication(): Promise<ApercuPublication> {
    return this.api.get<ApercuPublication>('/api/planning/publication');
  }
}

/** `?a=1&b=2` from the parameters that are actually set, or an empty string. */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, value);
    }
  }
  const rendered = search.toString();
  return rendered ? `?${rendered}` : '';
}
