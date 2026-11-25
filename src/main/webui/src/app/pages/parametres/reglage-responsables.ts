// The rules of the « Responsables de stand » card, kept out of the component so they
// are tested without rendering.

import { ReglageNoms, ReglagesResponsables } from '../../core/models';

/** What one stand does with the edition's setting: follows it, or overrides it either way. */
export type ModeStand = 'herite' | 'horaires' | 'noms';

/** One stand's row of the card; `ouverture` is a `datetime-local` value, empty for « at once ». */
export interface LigneStand {
  standId: string;
  mode: ModeStand;
  ouverture: string;
}

/** The edition's default, as the form holds it. */
export interface DefautEdition {
  noms: boolean;
  ouverture: string;
}

/** An ISO instant as the value of a `datetime-local` input (local time, minutes). */
export function toLocalInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The ISO instant of a `datetime-local` value; `null` when empty or not a date. */
export function fromLocalInput(valeur: string): string | null {
  if (!valeur) {
    return null;
  }
  const date = new Date(valeur);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function defaultOf(reglages: ReglagesResponsables): DefautEdition {
  return {
    noms: reglages.parDefaut.nomsVisibles,
    ouverture: toLocalInput(reglages.parDefaut.ouvertureLe),
  };
}

/** One row per stand, in the order given: an exception when the server holds one, else « as the edition ». */
export function rowsOf(reglages: ReglagesResponsables, standIds: readonly string[]): LigneStand[] {
  return standIds.map((standId) => {
    const exception = reglages.parStand[standId];
    return {
      standId,
      mode: !exception ? 'herite' : exception.nomsVisibles ? 'noms' : 'horaires',
      ouverture: toLocalInput(exception?.ouvertureLe ?? null),
    };
  });
}

/** What is sent: the default, and each stand shown — its exception, or `null` to lift it. */
export function toSettings(
  defaut: DefautEdition,
  lignes: readonly LigneStand[],
): ReglagesResponsables {
  // `null` lifts a stand's exception; a stand with no row here is left untouched by the server.
  const exceptions: Record<string, ReglageNoms | null> = {};
  for (const ligne of lignes) {
    exceptions[ligne.standId] =
      ligne.mode === 'herite'
        ? null
        : {
            nomsVisibles: ligne.mode === 'noms',
            ouvertureLe: ligne.mode === 'noms' ? fromLocalInput(ligne.ouverture) : null,
          };
  }
  return {
    parDefaut: {
      nomsVisibles: defaut.noms,
      ouvertureLe: defaut.noms ? fromLocalInput(defaut.ouverture) : null,
    },
    parStand: exceptions,
  };
}
