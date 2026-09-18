// The pure side of the Consignes page (issue #4): how a band and a window
// read, which dates can still take a consigne, how the form's state becomes
// the request the server expects, and the moves of the stands list — the
// merge with what the server proposes, the filters, « appliquer à la
// sélection ». Unit tested without rendering, same split as
// `journees-types.ts` next door.
//
// Hours come from the server as `HH:mm:ss` and leave as `HH:mm` (accepted).
// A window's end left empty reads « jusqu'à minuit », the convention of every
// dated window here, and travels as `null`.

import { jourSemaineDe, normaliseHour } from '../../core/horaire-stand';
import {
  ConsigneEdition,
  Creneau,
  DemandeConsigne,
  FenetreConsigne,
  LigneStandConsigne,
  OuvertureConsigne,
  Stand,
} from '../../core/models';
import { libelleJour, libelleJourSemaine } from '../stands/stand-horaires';

/* --------------------------------- wording --------------------------------- */

/** `2026-07-10` → `Vendredi 10/07`: a date as the table and the selectors say it. */
export function libelleDate(date: string): string {
  return `${libelleJourSemaine(jourSemaineDe(date))} ${libelleJour(date)}`;
}

/** `12:00:00` → `12h`, `12:30:00` → `12h30`: the way an organiser says an hour. */
export function heureLabel(heure: string): string {
  const [heures, minutes = '00'] = heure.split(':');
  return minutes === '00' ? `${Number(heures)}h` : `${Number(heures)}h${minutes}`;
}

/**
 * `12h–18h`, or `12h–minuit` for an open end. Called at runtime only
 * (`$localize`), never at module scope.
 */
export function bandeLabel(debut: string, fin: string | null): string {
  return `${heureLabel(debut)}–${fin ? heureLabel(fin) : minuitLabel()}`;
}

export function fenetreLabel(fenetre: FenetreConsigne): string {
  return bandeLabel(fenetre.debut, fenetre.fin);
}

function minuitLabel(): string {
  return $localize`:@@consignes.minuit:minuit`;
}

/* ---------------------------------- dates ---------------------------------- */

/**
 * The dates a consigne may still be laid on: the grid's dates strictly after
 * the server's today. A date already begun keeps for good the consigne that
 * governed it, so it is never offered. Nothing before the first read: without
 * a today, no date is known to be ahead.
 */
export function datesCandidates(creneaux: readonly Creneau[], aujourdhui: string | null): string[] {
  if (!aujourdhui) {
    return [];
  }
  return [...new Set(creneaux.map((creneau) => creneau.date))]
    .filter((date) => date > aujourdhui)
    .sort();
}

/** True when the date is today or before: read-only on screen. */
export function isPast(date: string, aujourdhui: string | null): boolean {
  return aujourdhui !== null && date <= aujourdhui;
}

/** How many distinct stands a consigne opens — one stand may hold several windows. */
export function openedStandsCount(consigne: ConsigneEdition): number {
  return new Set(consigne.ouvertures.map((ouverture) => ouverture.standId)).size;
}

/* ------------------------------- form state ------------------------------- */

/** A window as typed: `HH:mm`, the end empty for « jusqu'à minuit ». */
export interface FenetreSaisie {
  debut: string;
  fin: string;
}

/** One stand of the form: what the server said of it, and what was chosen. */
export interface StandForm {
  standId: string;
  standNom: string;
  coche: boolean;
  minutesPerdues: number;
  effectifHerite: number;
  exceptionDatee: boolean;
  /** Why the server set the stand aside, when it did. */
  motif: string | null;
  preCoche: boolean;
  fenetres: FenetreSaisie[];
  /** The headcount typed for the stand's windows, `null` to inherit. */
  effectif: number | null;
}

export interface ConsigneForm {
  dates: string[];
  fermetureDebut: string;
  /** Empty = until midnight. */
  fermetureFin: string;
  motif: string;
  prereglage: string | null;
  fenetres: FenetreSaisie[];
  stands: StandForm[];
}

/** `HH:mm:ss` or `HH:mm` → `HH:mm`; `null` (until midnight) → empty. */
export function heureSaisie(heure: string | null): string {
  return heure ? heure.slice(0, 5) : '';
}

export function fenetresSaisies(fenetres: readonly FenetreConsigne[]): FenetreSaisie[] {
  return fenetres.map((fenetre) => ({
    debut: heureSaisie(fenetre.debut),
    fin: heureSaisie(fenetre.fin),
  }));
}

/** The inverse: what the form sends, an empty end travelling as `null`. */
export function fenetresDemandees(fenetres: readonly FenetreSaisie[]): FenetreConsigne[] {
  return fenetres
    .filter((fenetre) => fenetre.debut !== '')
    .map((fenetre) => ({ debut: fenetre.debut, fin: fenetre.fin === '' ? null : fenetre.fin }));
}

/** An empty form, before any preset or row fills it. */
export function formVide(): ConsigneForm {
  return {
    dates: [],
    fermetureDebut: '12:00',
    fermetureFin: '18:00',
    motif: '',
    prereglage: null,
    fenetres: [],
    stands: [],
  };
}

/**
 * What blocks the request, in the order the form shows its fields; empty when
 * it can be sent. Codes rather than sentences: the dialog words them.
 */
export type ErreurForm = 'DATES' | 'FERMETURE_DEBUT' | 'FERMETURE_FIN' | 'MOTIF' | 'FENETRE';

export function erreursForm(form: ConsigneForm): ErreurForm[] {
  const erreurs: ErreurForm[] = [];
  if (form.dates.length === 0) {
    erreurs.push('DATES');
  }
  if (normaliseHour(form.fermetureDebut) === null) {
    erreurs.push('FERMETURE_DEBUT');
  }
  if (form.fermetureFin !== '' && normaliseHour(form.fermetureFin) === null) {
    erreurs.push('FERMETURE_FIN');
  }
  if (form.motif.trim() === '') {
    erreurs.push('MOTIF');
  }
  const fenetres = [
    ...form.fenetres,
    ...form.stands.filter((stand) => stand.coche).flatMap((stand) => stand.fenetres),
  ];
  if (fenetres.some((fenetre) => fenetreInvalide(fenetre))) {
    erreurs.push('FENETRE');
  }
  return erreurs;
}

/** A window whose start is missing or unreadable, or whose end is typed and unreadable. */
function fenetreInvalide(fenetre: FenetreSaisie): boolean {
  return (
    normaliseHour(fenetre.debut) === null ||
    (fenetre.fin !== '' && normaliseHour(fenetre.fin) === null)
  );
}

/**
 * The request the server expects, from the form as it stands: the day's
 * default windows, and one opening per window of every ticked stand, each
 * carrying the stand's headcount (or `null` to inherit). A ticked stand with
 * no window opens nothing — the band still closes it, like every other.
 */
export function buildDemande(form: ConsigneForm): DemandeConsigne {
  const ouvertures: OuvertureConsigne[] = [];
  for (const stand of form.stands) {
    if (!stand.coche) {
      continue;
    }
    for (const fenetre of fenetresDemandees(stand.fenetres)) {
      ouvertures.push({
        standId: stand.standId,
        debut: fenetre.debut,
        fin: fenetre.fin,
        effectif: stand.effectif,
      });
    }
  }
  return {
    dates: [...form.dates],
    fermetureDebut: form.fermetureDebut,
    fermetureFin: form.fermetureFin === '' ? null : form.fermetureFin,
    motif: form.motif.trim(),
    prereglage: form.prereglage,
    fenetres: fenetresDemandees(form.fenetres),
    ouvertures,
  };
}

/* ---------------------------- the stands list ---------------------------- */

/**
 * The stands as the form shows them, from what the server proposes for the
 * first date and the band.
 *
 * A stand already in the form keeps what was chosen for it — the list is
 * re-read on every change of date or band, and retyping sixty stands because
 * the band moved by an hour is the defect this avoids. A new stand starts from
 * the server: ticked when proposed, its windows being the ones the date's
 * existing consigne already opens on it, else the ones given for it (a row
 * being modified or prolonged), else the day's defaults; a stand set aside for
 * its dated hours starts unticked, but stays tickable.
 */
export function mergePreselection(
  lignes: readonly LigneStandConsigne[],
  fenetresParDefaut: readonly FenetreSaisie[],
  precedents: readonly StandForm[],
  ouverturesInitiales: readonly OuvertureConsigne[] = [],
): StandForm[] {
  const byId = new Map(precedents.map((stand) => [stand.standId, stand]));
  return lignes.map((ligne) => {
    const precedent = byId.get(ligne.standId);
    const ouvertures =
      ligne.ouvertures.length > 0
        ? ligne.ouvertures
        : ouverturesInitiales.filter((ouverture) => ouverture.standId === ligne.standId);
    const depuisOuvertures = ouvertures.length > 0;
    return {
      standId: ligne.standId,
      standNom: ligne.standNom,
      minutesPerdues: ligne.minutesPerdues,
      effectifHerite: ligne.effectifHerite,
      exceptionDatee: ligne.exceptionDatee,
      motif: ligne.motif,
      preCoche: ligne.preCoche,
      coche: precedent
        ? precedent.coche
        : depuisOuvertures || (ligne.preCoche && !ligne.exceptionDatee),
      fenetres: precedent
        ? precedent.fenetres
        : depuisOuvertures
          ? ouvertures.map((ouverture) => ({
              debut: heureSaisie(ouverture.debut),
              fin: heureSaisie(ouverture.fin),
            }))
          : fenetresParDefaut.map((fenetre) => ({ ...fenetre })),
      effectif: precedent
        ? precedent.effectif
        : (ouvertures.find((ouverture) => ouverture.effectif !== null)?.effectif ?? null),
    };
  });
}

/**
 * The day's default windows changed: every ticked-or-not stand still carrying
 * the previous defaults untouched follows them, and a stand whose windows were
 * typed by hand keeps them. Without this, picking a preset after the stands
 * loaded would leave sixty rows on the old windows.
 */
export function followDefaultWindows(
  rows: readonly StandForm[],
  anciennes: readonly FenetreSaisie[],
  nouvelles: readonly FenetreSaisie[],
): StandForm[] {
  const keyOf = (fenetres: readonly FenetreSaisie[]) =>
    fenetres.map((f) => `${f.debut}-${f.fin}`).join(',');
  const previousKey = keyOf(anciennes);
  return rows.map((row) =>
    keyOf(row.fenetres) === previousKey
      ? { ...row, fenetres: nouvelles.map((fenetre) => ({ ...fenetre })) }
      : row,
  );
}

/** How the list is narrowed: a name, a game category, a location, the premium tier. */
export interface FiltresStands {
  recherche: string;
  typologie: string;
  emplacement: string;
  premium: 'TOUS' | 'PREMIUM' | 'STANDARD';
}

export const FILTRES_VIDES: FiltresStands = {
  recherche: '',
  typologie: '',
  emplacement: '',
  premium: 'TOUS',
};

function normalise(value: string): string {
  return value.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

/**
 * The rows the filters keep. The referential the filters read is the stands
 * store's; a stand the store does not know (deleted since) matches by name
 * only, so it never vanishes from a list the server proposed.
 */
export function filterStands(
  rows: readonly StandForm[],
  stands: readonly Stand[],
  filtres: FiltresStands,
): StandForm[] {
  const byId = new Map(stands.map((stand) => [stand.id, stand]));
  const termes = normalise(filtres.recherche).split(/\s+/).filter(Boolean);
  return rows.filter((row) => {
    const stand = byId.get(row.standId);
    const nom = normalise(`${row.standNom} ${row.standId}`);
    if (!termes.every((terme) => nom.includes(terme))) {
      return false;
    }
    if (filtres.typologie && !(stand?.typologiesProposees ?? []).includes(filtres.typologie)) {
      return false;
    }
    if (filtres.emplacement && stand?.emplacement?.id !== filtres.emplacement) {
      return false;
    }
    if (filtres.premium === 'PREMIUM' && !stand?.premium) {
      return false;
    }
    if (filtres.premium === 'STANDARD' && stand?.premium) {
      return false;
    }
    return true;
  });
}

/** Ticks or unticks the displayed rows only — never the ones a filter hides. */
export function cocherAffiches(
  rows: readonly StandForm[],
  affiches: ReadonlySet<string>,
  coche: boolean,
): StandForm[] {
  return rows.map((row) => (affiches.has(row.standId) ? { ...row, coche } : row));
}

/**
 * « Appliquer à la sélection »: the same windows and/or the same headcount on
 * every displayed row that is ticked. A part left out (`undefined`) is not
 * touched — the bulk edits of the reference pages follow the same law.
 */
export function applyToSelection(
  rows: readonly StandForm[],
  affiches: ReadonlySet<string>,
  changement: { fenetres?: FenetreSaisie[]; effectif?: number | null },
): StandForm[] {
  return rows.map((row) => {
    if (!row.coche || !affiches.has(row.standId)) {
      return row;
    }
    return {
      ...row,
      fenetres: changement.fenetres
        ? changement.fenetres.map((fenetre) => ({ ...fenetre }))
        : row.fenetres,
      effectif: changement.effectif === undefined ? row.effectif : changement.effectif,
    };
  });
}

/**
 * `18h-22h, 9h-12h, 20h-`: one window per comma, hours as the stand windows
 * read them, an open end for « jusqu'à minuit ». `null` on anything
 * unreadable — a bulk gesture must not half-apply.
 */
export function parseFenetresSaisie(text: string): FenetreSaisie[] | null {
  const fenetres: FenetreSaisie[] = [];
  for (const brut of text.split(/[,;]/)) {
    const morceau = brut.trim();
    if (morceau === '') {
      continue;
    }
    const m = /^([^-–→]+)[-–→]\s*(.*)$/.exec(morceau);
    if (!m) {
      return null;
    }
    const debut = normaliseHour(m[1]);
    const fin = m[2].trim() === '' ? '' : normaliseHour(m[2]);
    if (debut === null || fin === null) {
      return null;
    }
    fenetres.push({ debut, fin });
  }
  return fenetres;
}

/** The inverse of {@link parseFenetresSaisie}: what a preset's windows read as on one line. */
export function formatFenetresSaisie(fenetres: readonly FenetreSaisie[]): string {
  return fenetres.map((fenetre) => `${fenetre.debut}-${fenetre.fin}`).join(', ');
}
