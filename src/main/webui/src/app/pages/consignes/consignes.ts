// The pure side of the Consignes page (issue #4): which dates can still take
// a consigne, how the form's state becomes the request the server expects,
// what blocks it, and the moves of the stands list — the merge with what the
// server proposes, the filters, « appliquer à la sélection ». Unit tested
// without rendering, same split as `journees-types.ts` next door. The wording
// every screen shares lives in `core/consigne-wording.ts`.
//
// Hours come from the server as `HH:mm:ss` and leave as `HH:mm` (accepted).
// A window's end left empty reads « jusqu'à minuit », the convention of every
// dated window here, and travels as `null`.

import { normaliseHour, splitHourRange } from '../../core/horaire-stand';
import {
  ConsigneEdition,
  Creneau,
  DemandeConsigne,
  FenetreConsigne,
  LigneStandConsigne,
  OuvertureConsigne,
  RepasConsigne,
  Stand,
} from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';

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
    .sort(compareCodeUnits);
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

/**
 * A window as typed: `HH:mm`, the end empty for « jusqu'à minuit », and the
 * headcount as text — empty to inherit. The headcount belongs to the window,
 * not to the stand: a morning at 2 and an evening at 7 are two windows of one
 * stand (product decision A). The day's default windows and a preset's carry
 * an empty one, a stand's window its own.
 */
export interface FenetreSaisie {
  debut: string;
  fin: string;
  effectif: string;
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
  /** The stand's windows, each with its own headcount. */
  fenetres: FenetreSaisie[];
  /** The stand's ceiling, what a typed headcount is checked against; `null` when the store does not know the stand. */
  effectifMax: number | null;
}

/**
 * The meal windows as typed, `HH:mm` and minutes as text: a field left empty
 * keeps the edition's value. The same shape serves the preset dialog.
 */
export interface RepasSaisie {
  midiDebut: string;
  midiFin: string;
  soirDebut: string;
  soirFin: string;
  coupureMinutes: string;
  justification: string;
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
  repas: RepasSaisie;
}

/** `HH:mm:ss` or `HH:mm` → `HH:mm`; `null` (until midnight) → empty. */
export function heureSaisie(heure: string | null): string {
  return heure ? heure.slice(0, 5) : '';
}

export function fenetresSaisies(fenetres: readonly FenetreConsigne[]): FenetreSaisie[] {
  return fenetres.map((fenetre) => ({
    debut: heureSaisie(fenetre.debut),
    fin: heureSaisie(fenetre.fin),
    effectif: '',
  }));
}

/** A stand's openings read into its windows, each keeping the headcount typed on it. */
export function ouverturesSaisies(ouvertures: readonly OuvertureConsigne[]): FenetreSaisie[] {
  return ouvertures.map((ouverture) => ({
    debut: heureSaisie(ouverture.debut),
    fin: heureSaisie(ouverture.fin),
    effectif: ouverture.effectif === null ? '' : String(ouverture.effectif),
  }));
}

/** The headcount field read: a positive whole number, or `null` for an empty field. Unreadable text is `erreursForm`'s to name. */
export function effectifDemande(texte: string): number | null {
  const propre = texte.trim();
  if (propre === '') {
    return null;
  }
  const valeur = Number(propre);
  return Number.isInteger(valeur) && valeur > 0 ? valeur : null;
}

/** True when the field holds something that is not a positive whole number — `0`, `-1`, `2.5`, `abc`. */
export function effectifInvalide(texte: string): boolean {
  return texte.trim() !== '' && effectifDemande(texte) === null;
}

/** The inverse: what the form sends, an empty end travelling as `null`. */
export function fenetresDemandees(fenetres: readonly FenetreSaisie[]): FenetreConsigne[] {
  return fenetres
    .filter((fenetre) => fenetre.debut !== '')
    .map((fenetre) => ({ debut: fenetre.debut, fin: fenetre.fin === '' ? null : fenetre.fin }));
}

/** No meal window restated: the edition's apply. */
export function repasVide(): RepasSaisie {
  return {
    midiDebut: '',
    midiFin: '',
    soirDebut: '',
    soirFin: '',
    coupureMinutes: '',
    justification: '',
  };
}

/** What a consigne or a preset carries, read into the fields; `null` reads as nothing typed. */
export function repasSaisie(repas: RepasConsigne | null): RepasSaisie {
  if (!repas) {
    return repasVide();
  }
  return {
    midiDebut: heureSaisie(repas.midiDebut),
    midiFin: heureSaisie(repas.midiFin),
    soirDebut: heureSaisie(repas.soirDebut),
    soirFin: heureSaisie(repas.soirFin),
    coupureMinutes: repas.coupureMinutes === null ? '' : String(repas.coupureMinutes),
    justification: repas.justification,
  };
}

/** True when no window and no break is typed — a justification alone restates nothing. */
export function repasFormEmpty(repas: RepasSaisie): boolean {
  return (
    repas.midiDebut === '' &&
    repas.midiFin === '' &&
    repas.soirDebut === '' &&
    repas.soirFin === '' &&
    repas.coupureMinutes.trim() === ''
  );
}

/**
 * The inverse of {@link repasSaisie}: `null` when nothing is restated — the
 * edition's windows apply and the server stores no override — else every
 * field, an empty one travelling as `null` to keep the edition's value.
 */
export function repasDemande(repas: RepasSaisie): RepasConsigne | null {
  if (repasFormEmpty(repas)) {
    return null;
  }
  const heure = (value: string) => (value === '' ? null : value);
  const coupure = repas.coupureMinutes.trim();
  return {
    midiDebut: heure(repas.midiDebut),
    midiFin: heure(repas.midiFin),
    soirDebut: heure(repas.soirDebut),
    soirFin: heure(repas.soirFin),
    coupureMinutes: coupure === '' ? null : Number(coupure),
    justification: repas.justification.trim(),
  };
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
    repas: repasVide(),
  };
}

/**
 * What blocks the request, in the order the form shows its fields; empty when
 * it can be sent. Codes rather than sentences: the dialog words them.
 */
export type ErreurForm =
  | 'DATES'
  | 'FERMETURE_DEBUT'
  | 'FERMETURE_FIN'
  | 'MOTIF'
  | 'FENETRE'
  | 'FENETRE_ORDRE'
  | 'FENETRE_BANDE'
  | 'EFFECTIF'
  | 'EFFECTIF_MAX'
  | 'REPAS_FENETRE'
  | 'REPAS_ORDRE'
  | 'REPAS_COUPURE'
  | 'REPAS_JUSTIFICATION';

/**
 * The server's rules said before it says them: a date the form does not
 * offer (a deep link on a past day), a window ending at or before its start,
 * a window the band swallows whole, a headcount that is not a positive whole
 * number or above the stand's ceiling. `datesOffertes` left out checks the
 * dates for presence only.
 */
export function erreursForm(
  form: ConsigneForm,
  datesOffertes: readonly string[] | null = null,
): ErreurForm[] {
  const erreurs: ErreurForm[] = [];
  if (
    form.dates.length === 0 ||
    (datesOffertes !== null && form.dates.some((date) => !datesOffertes.includes(date)))
  ) {
    erreurs.push('DATES');
  }
  const bandeDebut = normaliseHour(form.fermetureDebut);
  if (bandeDebut === null) {
    erreurs.push('FERMETURE_DEBUT');
  }
  const bandeFin = form.fermetureFin === '' ? null : normaliseHour(form.fermetureFin);
  if (form.fermetureFin !== '' && bandeFin === null) {
    erreurs.push('FERMETURE_FIN');
  }
  if (form.motif.trim() === '') {
    erreurs.push('MOTIF');
  }
  const standsCoches = form.stands.filter((stand) => stand.coche);
  const fenetres = [...form.fenetres, ...standsCoches.flatMap((stand) => stand.fenetres)];
  if (fenetres.some((fenetre) => fenetreInvalide(fenetre))) {
    erreurs.push('FENETRE');
  }
  if (fenetres.some((fenetre) => windowReversed(fenetre))) {
    erreurs.push('FENETRE_ORDRE');
  }
  if (
    bandeDebut !== null &&
    (form.fermetureFin === '' || bandeFin !== null) &&
    fenetres.some((fenetre) => windowInsideBand(fenetre, bandeDebut, bandeFin))
  ) {
    erreurs.push('FENETRE_BANDE');
  }
  const effectifs = standsCoches.flatMap((stand) =>
    stand.fenetres.map((fenetre) => ({ texte: fenetre.effectif, max: stand.effectifMax })),
  );
  if (effectifs.some(({ texte }) => effectifInvalide(texte))) {
    erreurs.push('EFFECTIF');
  }
  if (
    effectifs.some(({ texte, max }) => {
      const effectif = effectifDemande(texte);
      return effectif !== null && max !== null && effectif > max;
    })
  ) {
    erreurs.push('EFFECTIF_MAX');
  }
  erreurs.push(...erreursRepas(form.repas));
  return erreurs;
}

/** Minutes from midnight of a readable `HH:mm`; an empty or `00:00` end is the day's end. */
function minutesOf(heure: string, ouverte: boolean): number | null {
  if (ouverte && (heure === '' || normaliseHour(heure) === '00:00')) {
    return 24 * 60;
  }
  const propre = normaliseHour(heure);
  if (propre === null) {
    return null;
  }
  const [h, m] = propre.split(':').map(Number);
  return h * 60 + m;
}

/** Both bounds readable, and the end not after the start. */
function windowReversed(fenetre: FenetreSaisie): boolean {
  const debut = minutesOf(fenetre.debut, false);
  const fin = minutesOf(fenetre.fin, true);
  return debut !== null && fin !== null && fin <= debut;
}

/** A readable, well-ordered window the band covers entirely: it would open nothing. */
function windowInsideBand(
  fenetre: FenetreSaisie,
  bandeDebut: string,
  bandeFin: string | null,
): boolean {
  const debut = minutesOf(fenetre.debut, false);
  const fin = minutesOf(fenetre.fin, true);
  const bDebut = minutesOf(bandeDebut, false);
  const bFin = minutesOf(bandeFin ?? '', true);
  if (debut === null || fin === null || bDebut === null || bFin === null || fin <= debut) {
    return false;
  }
  return bDebut <= debut && fin <= bFin;
}

/**
 * What blocks the meal section, the server's rules said before it says them:
 * a window needs both bounds (readable) or none, the break is a whole number
 * of minutes above zero, and the justification is required as soon as one
 * field is set. Nothing typed, nothing blocked.
 */
export function erreursRepas(repas: RepasSaisie): ErreurForm[] {
  const erreurs: ErreurForm[] = [];
  if (
    fenetreRepasInvalide(repas.midiDebut, repas.midiFin) ||
    fenetreRepasInvalide(repas.soirDebut, repas.soirFin)
  ) {
    erreurs.push('REPAS_FENETRE');
  }
  if (
    mealWindowReversed(repas.midiDebut, repas.midiFin) ||
    mealWindowReversed(repas.soirDebut, repas.soirFin)
  ) {
    erreurs.push('REPAS_ORDRE');
  }
  const coupure = repas.coupureMinutes.trim();
  if (coupure !== '' && !(Number.isInteger(Number(coupure)) && Number(coupure) > 0)) {
    erreurs.push('REPAS_COUPURE');
  }
  if (!repasFormEmpty(repas) && repas.justification.trim() === '') {
    erreurs.push('REPAS_JUSTIFICATION');
  }
  return erreurs;
}

/** Both bounds readable and the window not ending after it starts — a meal window has no open end. */
function mealWindowReversed(debut: string, fin: string): boolean {
  const d = normaliseHour(debut);
  const f = normaliseHour(fin);
  return d !== null && f !== null && f <= d;
}

/** One bound without the other, or a bound that cannot be read. */
function fenetreRepasInvalide(debut: string, fin: string): boolean {
  if (debut === '' && fin === '') {
    return false;
  }
  return normaliseHour(debut) === null || normaliseHour(fin) === null;
}

/**
 * « Aligner le soir sur la compensation »: the evening meal window becomes the
 * earliest start and the latest end of the day's default windows, so that a
 * service starting when the compensation opens or ending when it closes owes
 * no break — the teams ate during the closed band. An open end (until
 * midnight) reads as `23:59`, the last minute a time field can hold. The
 * justification is filled when empty, never overwritten. Without a readable
 * window, nothing moves.
 */
export function alignSoirOnCompensation(
  repas: RepasSaisie,
  fenetres: readonly FenetreSaisie[],
): RepasSaisie {
  const bornes = fenetres
    .map((fenetre) => ({
      debut: normaliseHour(fenetre.debut),
      fin: fenetre.fin === '' ? '23:59' : normaliseHour(fenetre.fin),
    }))
    .filter((borne): borne is { debut: string; fin: string } => !!borne.debut && !!borne.fin);
  if (bornes.length === 0) {
    return repas;
  }
  const soirDebut = bornes.map((borne) => borne.debut).sort(compareCodeUnits)[0];
  const soirFin =
    bornes
      .map((borne) => borne.fin)
      .sort(compareCodeUnits)
      .at(-1) ?? soirDebut;
  return {
    ...repas,
    soirDebut,
    soirFin,
    justification:
      repas.justification.trim() === ''
        ? $localize`:@@consignes.form.repas.justification.fermeture:Les équipes mangent pendant la fermeture`
        : repas.justification,
  };
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
 * carrying its own headcount (or `null` to inherit). A ticked stand with no
 * window opens nothing — the band still closes it, like every other. The
 * meal section travels as `null` when nothing is restated in it.
 */
export function buildDemande(form: ConsigneForm): DemandeConsigne {
  const ouvertures: OuvertureConsigne[] = [];
  for (const stand of form.stands) {
    if (!stand.coche) {
      continue;
    }
    for (const fenetre of stand.fenetres) {
      if (fenetre.debut === '') {
        continue;
      }
      ouvertures.push({
        standId: stand.standId,
        debut: fenetre.debut,
        fin: fenetre.fin === '' ? null : fenetre.fin,
        effectif: effectifDemande(fenetre.effectif),
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
    repas: repasDemande(form.repas),
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
  effectifMaxByStand: ReadonlyMap<string, number> = new Map(),
): StandForm[] {
  const byId = new Map(precedents.map((stand) => [stand.standId, stand]));
  return lignes.map((ligne) => {
    const precedent = byId.get(ligne.standId);
    const ouvertures =
      ligne.ouvertures.length > 0
        ? ligne.ouvertures
        : ouverturesInitiales.filter((ouverture) => ouverture.standId === ligne.standId);
    const depuisOuvertures = ouvertures.length > 0;
    const fenetresInitiales = (): FenetreSaisie[] =>
      depuisOuvertures
        ? ouverturesSaisies(ouvertures)
        : fenetresParDefaut.map((fenetre) => ({ ...fenetre, effectif: '' }));
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
      fenetres: precedent?.fenetres ?? fenetresInitiales(),
      effectifMax: effectifMaxByStand.get(ligne.standId) ?? null,
    };
  });
}

/**
 * The day's default windows changed: every ticked-or-not stand still carrying
 * the previous defaults' hours follows them, and a stand whose windows were
 * typed by hand keeps them. Without this, picking a preset after the stands
 * loaded would leave sixty rows on the old windows. A headcount typed on a
 * row's window stays on the window of the same rank — the hours moved, not
 * what was decided for the stand.
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
      ? {
          ...row,
          fenetres: nouvelles.map((fenetre, index) => ({
            ...fenetre,
            effectif: row.fenetres[index]?.effectif ?? '',
          })),
        }
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
 * every window of every displayed row that is ticked. A part left out
 * (`undefined`) is not touched — the bulk edits of the reference pages follow
 * the same law; windows given without a headcount keep the one typed on the
 * row's window of the same rank.
 */
export function applyToSelection(
  rows: readonly StandForm[],
  affiches: ReadonlySet<string>,
  changement: { fenetres?: FenetreSaisie[]; effectif?: string },
): StandForm[] {
  return rows.map((row) => {
    if (!row.coche || !affiches.has(row.standId)) {
      return row;
    }
    const fenetres = changement.fenetres
      ? changement.fenetres.map((fenetre, index) => ({
          ...fenetre,
          effectif: row.fenetres[index]?.effectif ?? '',
        }))
      : row.fenetres;
    return {
      ...row,
      fenetres:
        changement.effectif === undefined
          ? fenetres
          : fenetres.map((fenetre) => ({ ...fenetre, effectif: changement.effectif ?? '' })),
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
    const plage = splitHourRange(morceau);
    if (!plage) {
      return null;
    }
    const debut = normaliseHour(plage[0]);
    const fin = plage[1].trim() === '' ? '' : normaliseHour(plage[1]);
    if (debut === null || fin === null) {
      return null;
    }
    fenetres.push({ debut, fin, effectif: '' });
  }
  return fenetres;
}

/** The inverse of {@link parseFenetresSaisie}: what a preset's windows read as on one line. */
export function formatFenetresSaisie(fenetres: readonly FenetreSaisie[]): string {
  return fenetres.map((fenetre) => `${fenetre.debut}-${fenetre.fin}`).join(', ');
}
