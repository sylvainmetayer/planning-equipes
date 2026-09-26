// « Qui peut tenir ce siège ? » then « Placer », wherever a seat is asked about:
// the Siège panel of the Journée, and the Diagnostic's cards, which open the
// same dialog in place rather than sending the reader to another screen. The
// dialog chooses; this writes — the placement, then the lock the box asked for.

import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { PostesApi } from '../../core/api/postes-api';
import { errorPrefix } from '../../core/error-message';
import { Avertissement } from '../../core/models';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { BenchChoice, BenchDialogData, openBenchDialog } from './bench-dialog';
import { qualityWarning } from './seat';

/** What a placement did, in words. */
export interface PlacementOutcome {
  message: string;
  /** The quality level lost points, or the lock froze a seat already in breach: accepted, but said. */
  warning: string | null;
  /** The placement went through but the lock did not: said rather than undone. Empty otherwise. */
  lockError: string;
}

@Injectable({ providedIn: 'root' })
export class SeatPlacement {
  private readonly dialog = inject(MatDialog);
  private readonly postesApi = inject(PostesApi);
  private readonly verrous = inject(VerrouillageStore);

  /** The bench of one empty seat, in a dialog; resolves with the person chosen, `undefined` when dismissed. */
  choose(data: BenchDialogData): Promise<BenchChoice | undefined> {
    return firstValueFrom(openBenchDialog(this.dialog, data).afterClosed());
  }

  /**
   * Seats the person chosen and, the box left ticked, locks them there so the
   * next solve keeps the placement. A refused placement throws; a refused
   * lock does not — the placement is written, and undoing it would be worse.
   */
  async place(
    posteId: string,
    creneauId: number,
    choice: BenchChoice,
    nom: string,
  ): Promise<PlacementOutcome> {
    const placement = await this.postesApi.place(posteId, choice.animateurId);
    let message = $localize`:@@deplacement.place:${nom}:cible: est placé(e) sur ce siège.`;
    const warnings = [qualityWarning(placement.delta)];
    let lockError = '';
    if (choice.keep) {
      try {
        const avertissements = await this.verrous.create({
          type: 'ANIMATEUR_CRENEAU',
          animateurId: choice.animateurId,
          creneauId,
        });
        warnings.push(lockWarning(avertissements));
        message = $localize`:@@siege.placer.garde:${nom}:nom: est placé(e) sur ce siège, et y restera au prochain calcul.`;
      } catch (error) {
        const cause = errorPrefix(error);
        lockError = $localize`:@@siege.placer.sansVerrou:Placé(e), mais le verrou n'a pas pu être posé : ${cause}:erreur:`;
      }
    }
    return { message, warning: warnings.filter(Boolean).join(' ') || null, lockError };
  }
}

/**
 * What the server wants read about a lock just laid — seats it freezes that
 * already break a hard rule — as one sentence of the panel, which the
 * notification journal never sees. Null when it said nothing.
 */
export function lockWarning(avertissements: readonly Avertissement[]): string | null {
  return avertissements.length > 0
    ? avertissements.map((avertissement) => avertissement.message).join(' ')
    : null;
}
