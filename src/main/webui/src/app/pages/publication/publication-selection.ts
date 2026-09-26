import { Injectable, signal } from '@angular/core';

/**
 * Who the admin is holding back from the next publication, shared by the
 * two halves of the Envoyer tab: the review table's checkbox and the
 * permanent table's « Différer » on a row. Provided by the page, so it lives
 * and dies with it.
 *
 * <p>Not view state and deliberately not in the URL: it is a decision about
 * to be carried out, not a way of looking at the list, and a shared link that
 * silently carried somebody's exclusion would be the worst possible thing to
 * paste into a chat.</p>
 */
@Injectable()
export class PublicationSelection {
  private readonly _excluded = signal<ReadonlySet<string>>(new Set());
  readonly excluded = this._excluded.asReadonly();

  isExcluded(animateurId: string): boolean {
    return this._excluded().has(animateurId);
  }

  /** Puts somebody back in the send (`prevenir`), or holds them back. */
  setExcluded(animateurId: string, excluded: boolean): void {
    const next = new Set(this._excluded());
    if (excluded) {
      next.add(animateurId);
    } else {
      next.delete(animateurId);
    }
    this._excluded.set(next);
  }

  /** Keeps only the people a fresh preview still names. */
  retain(recipients: ReadonlySet<string>): void {
    this._excluded.set(new Set([...this._excluded()].filter((id) => recipients.has(id))));
  }

  clear(): void {
    this._excluded.set(new Set());
  }
}
