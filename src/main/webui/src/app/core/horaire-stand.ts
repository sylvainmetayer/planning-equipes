// Resolution of a stand's recurring horaires into per-day windows, plus the
// short human summaries the stands table and the rule editor display.
//
// This mirrors the backend `HoraireStandResolver` / `Creneau#segmentsOuvertsMinutes`
// and MUST stay in sync with it: it exists so the editor can preview a rule
// live, without a round-trip per keystroke. It is deliberately the only place
// the frontend re-implements domain logic — keep any change here and there in
// the same commit.

import { parseDateKey, toDateKey } from './date-utils';
import { intlLocale } from './locale';
import {
  FenetreHoraire,
  HoraireStand,
  JourSemaine,
  ModeHoraire,
  Stand,
  TypeAnomalieOuverture,
  TypeJoursHoraire,
} from './models';

/** Same order as the backend `TypeJoursHoraire`: least specific first. */
const SPECIFICITE: Record<TypeJoursHoraire, number> = {
  TOUS: 0,
  JOURS_SEMAINE: 1,
  PLAGE: 2,
  DATES: 3,
};

const JOURS_SEMAINE: JourSemaine[] = [
  'SUNDAY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
];

/** What one day of the event resolves to, and which layer decided it. */
export interface JourResolu {
  date: string;
  /** `null` = nothing states anything about this day: open all day, the default. */
  mode: ModeHoraire | null;
  fenetres: FenetreHoraire[];
  source: 'EXCEPTION' | 'REGLE' | 'DEFAUT';
}

export function jourSemaineDe(date: string): JourSemaine {
  return JOURS_SEMAINE[parseDateKey(date).getDay()];
}

/** True when `horaire` has something to say about `date`. */
export function couvreJour(horaire: HoraireStand, date: string): boolean {
  switch (horaire.jours) {
    case 'TOUS':
      return true;
    case 'JOURS_SEMAINE':
      return horaire.joursSemaine.includes(jourSemaineDe(date));
    case 'PLAGE':
      return (
        (!horaire.dateDebut || date >= horaire.dateDebut) &&
        (!horaire.dateFin || date <= horaire.dateFin)
      );
    case 'DATES':
      return horaire.dates.includes(date);
  }
}

function isValidWindow(fenetre: { heureDebut: string; heureFin: string | null }): boolean {
  return !!fenetre.heureDebut && (!fenetre.heureFin || fenetre.heureFin > fenetre.heureDebut);
}

/**
 * The whole layering, per day: a dated exception wins outright, otherwise the
 * covering rules of highest specificity do, otherwise the day is open by
 * default. Mirrors `HoraireStandResolver#resoudreJour`, tie-break included
 * (OUVERTURE wins between two same-specificity rules of opposite mode — a
 * combination the backend rejects at write time anyway).
 */
export function resoudreJour(stand: Stand, date: string): JourResolu {
  const ouvertures = (stand.ouvertures ?? []).filter((ouverture) => ouverture.date === date);
  const fermetures = (stand.indisponibilites ?? []).filter((indispo) => indispo.date === date);
  if (ouvertures.length > 0) {
    return { date, mode: 'OUVERTURE', fenetres: sort(ouvertures), source: 'EXCEPTION' };
  }
  if (fermetures.length > 0) {
    return { date, mode: 'FERMETURE', fenetres: sort(fermetures), source: 'EXCEPTION' };
  }

  const horaires = stand.horaires ?? [];
  const retenues = reglesRetenues(horaires, date);
  if (retenues.length === 0) {
    return { date, mode: null, fenetres: [], source: 'DEFAUT' };
  }
  const fenetres = retenues.flatMap((index) => horaires[index].fenetres.filter(isValidWindow));
  return { date, mode: horaires[retenues[0]].mode, fenetres: sort(fenetres), source: 'REGLE' };
}

/**
 * Which rules decide `date`, by index: the covering rules of highest
 * specificity whose mode won, in the stand's order — empty when none covers
 * the day. Mirrors `HoraireStandResolver#resolveDayWithRules`, the variant the
 * backend's analysis replays.
 */
export function reglesRetenues(horaires: readonly HoraireStand[], date: string): number[] {
  const couvrantes = horaires
    .map((horaire, index) => ({ horaire, index }))
    .filter(({ horaire }) => couvreJour(horaire, date) && horaire.fenetres.some(isValidWindow));
  if (couvrantes.length === 0) {
    return [];
  }
  const specificiteMax = Math.max(...couvrantes.map(({ horaire }) => SPECIFICITE[horaire.jours]));
  const gagnantes = couvrantes.filter(
    ({ horaire }) => SPECIFICITE[horaire.jours] === specificiteMax,
  );
  const mode: ModeHoraire = gagnantes.some(({ horaire }) => horaire.mode === 'OUVERTURE')
    ? 'OUVERTURE'
    : 'FERMETURE';
  return gagnantes.filter(({ horaire }) => horaire.mode === mode).map(({ index }) => index);
}

export function resoudreHoraires(stand: Stand, dates: readonly string[]): JourResolu[] {
  return dates.map((date) => resoudreJour(stand, date));
}

function sort(fenetres: readonly FenetreHoraire[]): FenetreHoraire[] {
  return [...fenetres]
    .map((fenetre) => ({
      heureDebut: fenetre.heureDebut,
      heureFin: fenetre.heureFin,
      // Carried only when named, so a window without one keeps its historical shape.
      ...(fenetre.effectif !== null && fenetre.effectif !== undefined
        ? { effectif: fenetre.effectif }
        : {}),
    }))
    .sort((a, b) => a.heureDebut.localeCompare(b.heureDebut));
}

/**
 * Whether a window's effectif, as typed, can be saved: empty means "the
 * stand's minimum" and is fine; anything else must be a whole number of at
 * least one — a window nobody should staff is a closure, not a zero.
 */
export function effectifFenetreInvalide(
  effectif: number | null | undefined,
  effectifMax?: number,
): boolean {
  if (effectif === null || effectif === undefined) {
    return false;
  }
  if (!Number.isInteger(effectif) || effectif < 1) {
    return true;
  }
  // Mirrors `StandValidator.checkEffectifFenetre`: a window cannot make
  // mandatory more seats than the stand is declared able to hold.
  return (
    effectifMax !== undefined && Number.isFinite(effectifMax) && effectif > Number(effectifMax)
  );
}

/** `09:00:00` → `09:00`; leaves anything already short alone. */
export function heureCourte(heure: string): string {
  return heure.length > 5 ? heure.slice(0, 5) : heure;
}

/**
 * `10:00 → 12:00`, or `14:00 → fermeture` for an open-ended window — followed
 * by ` ×3` when the window names its own effectif, so a preview never hides
 * that the seats differ from the stand's minimum.
 */
export function decrireFenetre(fenetre: FenetreHoraire, libelleFermeture: string): string {
  const fin = fenetre.heureFin ? heureCourte(fenetre.heureFin) : libelleFermeture;
  const effectif =
    fenetre.effectif !== null && fenetre.effectif !== undefined ? ` ×${fenetre.effectif}` : '';
  return `${heureCourte(fenetre.heureDebut)} → ${fin}${effectif}`;
}

/**
 * Compact summary of a stand's horaires for the stands table — "2 fenêtres ·
 * tous les jours" reads at a glance where the raw "24" of the dated form did
 * not. Labels come from the caller so this file stays free of `$localize`.
 */
export function resumerHoraires(
  stand: Stand,
  libelles: { aucun: string; regles: (n: number) => string; exceptions: (n: number) => string },
): string {
  const regles = (stand.horaires ?? []).length;
  const exceptions = (stand.indisponibilites ?? []).length + (stand.ouvertures ?? []).length;
  if (regles === 0 && exceptions === 0) {
    return libelles.aucun;
  }
  const morceaux: string[] = [];
  if (regles > 0) {
    morceaux.push(libelles.regles(regles));
  }
  if (exceptions > 0) {
    morceaux.push(libelles.exceptions(exceptions));
  }
  return morceaux.join(' · ');
}

/* ----------------- how the rules of one stand are written ----------------- */
//
// Mirrors `HoraireRuleOverlaps` (backend, `service/analyse`): what the resolver
// settles in silence between the rules of one stand. The editor runs it on the
// rules being typed and warns under the card concerned; the Ouvertures screen
// reads the backend's own. Both sides are held to the same answers by
// `horaire-stand-anomalies.cas.json`, which the Java test and this file's spec
// both read — change one side, the other's test fails.

/** One day of the edition: a date carrying a créneau, and the end of its opening span (`HH:MM`, `24:00` at most). */
export interface JourEdition {
  date: string;
  fin: string;
}

/** Two rules deciding a same day whose windows overlap. Indexes into the stand's rules. */
export interface ReglesChevauchantes {
  regle: number;
  autreRegle: number;
  date: string;
  debut: string;
  fin: string;
  effectif: number | null;
  autreEffectif: number | null;
}

/** A rule covering days of the edition that decides none of them. */
export interface RegleMasquee {
  regle: number;
  /** The rules that decide its days instead. */
  masquantes: number[];
  /** Some of its days are stated by a dated exception. */
  exceptions: boolean;
}

/** Two windows of one rule (`regle`), or of one day's dated openings (`date`), overlapping at different headcounts. */
export interface FenetresChevauchantes {
  regle: number | null;
  date: string | null;
  fenetre: number;
  autreFenetre: number;
  debut: string;
  fin: string;
  effectif: number | null;
  autreEffectif: number | null;
}

export interface AnomaliesHoraires {
  reglesChevauchantes: ReglesChevauchantes[];
  reglesMasquees: RegleMasquee[];
  fenetresChevauchantes: FenetresChevauchantes[];
}

const MINUTES_PER_DAY = 24 * 60;

interface FenetreLue {
  heureDebut: string;
  heureFin: string | null;
  effectif?: number | null;
}

function minutesOf(heure: string): number {
  const [h, m] = heure.slice(0, 5).split(':').map(Number);
  return h * 60 + m;
}

function hourOf(minutes: number): string {
  const heures = String(Math.floor(minutes / 60)).padStart(2, '0');
  return `${heures}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Overlap of two windows in minutes, an open end running to `finJour`; `null` when they only touch. */
function recouvrement(a: FenetreLue, b: FenetreLue, finJour: number): [number, number] | null {
  const cap = Math.min(finJour, MINUTES_PER_DAY);
  const start = Math.max(minutesOf(a.heureDebut), minutesOf(b.heureDebut));
  const end = Math.min(
    a.heureFin ? minutesOf(a.heureFin) : cap,
    b.heureFin ? minutesOf(b.heureFin) : cap,
  );
  return end > start ? [start, end] : null;
}

function effectifOrDefault(
  effectif: number | null | undefined,
  defaut: number | null,
): number | null {
  return effectif ?? defaut;
}

/**
 * The three findings on one stand's schedule, judged on the edition's days
 * (`jours`, sorted). Without a day only the windows of one rule are read —
 * nothing can be masked or merged on a calendar that does not exist yet.
 * `effectifParDefaut` is what a window naming no headcount asks for (the
 * stand's minimum), `null` when unknown.
 */
export function anomaliesHoraires(
  stand: Pick<Stand, 'horaires' | 'ouvertures' | 'indisponibilites'>,
  jours: readonly JourEdition[],
  effectifParDefaut: number | null,
): AnomaliesHoraires {
  const horaires = stand.horaires ?? [];
  const ouvertures = stand.ouvertures ?? [];
  const exceptions = new Set(
    [...(stand.indisponibilites ?? []), ...ouvertures].map((exception) => exception.date),
  );
  const decideurs = new Map<string, number[]>();
  for (const jour of jours) {
    if (!exceptions.has(jour.date)) {
      decideurs.set(jour.date, reglesRetenues(horaires, jour.date));
    }
  }
  return {
    reglesChevauchantes: reglesChevauchantes(horaires, jours, decideurs, effectifParDefaut),
    reglesMasquees: reglesMasquees(horaires, jours, exceptions, decideurs),
    fenetresChevauchantes: fenetresChevauchantes(horaires, ouvertures, jours, effectifParDefaut),
  };
}

function reglesChevauchantes(
  horaires: readonly HoraireStand[],
  jours: readonly JourEdition[],
  decideurs: ReadonlyMap<string, number[]>,
  effectifParDefaut: number | null,
): ReglesChevauchantes[] {
  const trouvees: ReglesChevauchantes[] = [];
  const seen = new Set<string>();
  for (const jour of jours) {
    const regles = decideurs.get(jour.date) ?? [];
    for (let a = 0; a < regles.length; a++) {
      for (let b = a + 1; b < regles.length; b++) {
        const i = Math.min(regles[a], regles[b]);
        const j = Math.max(regles[a], regles[b]);
        if (seen.has(`${i}#${j}`)) {
          continue;
        }
        const trouvee = premierRecouvrement(horaires, i, j, jour, effectifParDefaut);
        if (trouvee) {
          seen.add(`${i}#${j}`);
          trouvees.push(trouvee);
        }
      }
    }
  }
  return trouvees;
}

function premierRecouvrement(
  horaires: readonly HoraireStand[],
  i: number,
  j: number,
  jour: JourEdition,
  effectifParDefaut: number | null,
): ReglesChevauchantes | null {
  for (const a of horaires[i].fenetres.filter(isValidWindow)) {
    for (const b of horaires[j].fenetres.filter(isValidWindow)) {
      const bornes = recouvrement(a, b, minutesOf(jour.fin));
      if (bornes) {
        return {
          regle: i,
          autreRegle: j,
          date: jour.date,
          debut: hourOf(bornes[0]),
          fin: hourOf(bornes[1]),
          effectif: effectifOrDefault(a.effectif, effectifParDefaut),
          autreEffectif: effectifOrDefault(b.effectif, effectifParDefaut),
        };
      }
    }
  }
  return null;
}

function reglesMasquees(
  horaires: readonly HoraireStand[],
  jours: readonly JourEdition[],
  exceptions: ReadonlySet<string>,
  decideurs: ReadonlyMap<string, number[]>,
): RegleMasquee[] {
  const trouvees: RegleMasquee[] = [];
  horaires.forEach((horaire, index) => {
    if (!horaire.fenetres.some(isValidWindow)) {
      // Already ignored by the resolver and refused at write time.
      return;
    }
    const couverts = jours.map((jour) => jour.date).filter((date) => couvreJour(horaire, date));
    if (couverts.length === 0) {
      // Outside the edition: the windows without effect say it already.
      return;
    }
    if (couverts.some((date) => (decideurs.get(date) ?? []).includes(index))) {
      return;
    }
    const masquantes = new Set<number>();
    let byExceptions = false;
    for (const date of couverts) {
      if (exceptions.has(date)) {
        byExceptions = true;
      } else {
        (decideurs.get(date) ?? []).forEach((autre) => masquantes.add(autre));
      }
    }
    trouvees.push({
      regle: index,
      masquantes: [...masquantes].sort((x, y) => x - y),
      exceptions: byExceptions,
    });
  });
  return trouvees;
}

function fenetresChevauchantes(
  horaires: readonly HoraireStand[],
  ouvertures: readonly (FenetreLue & { date: string })[],
  jours: readonly JourEdition[],
  effectifParDefaut: number | null,
): FenetresChevauchantes[] {
  const trouvees: FenetresChevauchantes[] = [];
  horaires.forEach((horaire, regle) => {
    if (horaire.mode !== 'OUVERTURE') {
      // A closure carries no headcount: nothing to decide between its windows.
      return;
    }
    const fins = jours
      .filter((jour) => couvreJour(horaire, jour.date))
      .map((jour) => minutesOf(jour.fin));
    const finJour = fins.length > 0 ? Math.max(...fins) : MINUTES_PER_DAY;
    const fenetres = horaire.fenetres;
    for (let a = 0; a < fenetres.length; a++) {
      for (let b = a + 1; b < fenetres.length; b++) {
        const trouvee = windowPair(fenetres[a], fenetres[b], finJour, effectifParDefaut);
        if (trouvee) {
          trouvees.push({ regle, date: null, fenetre: a, autreFenetre: b, ...trouvee });
        }
      }
    }
  });
  for (let a = 0; a < ouvertures.length; a++) {
    for (let b = a + 1; b < ouvertures.length; b++) {
      const date = ouvertures[a].date;
      if (!date || date !== ouvertures[b].date) {
        continue;
      }
      const jour = jours.find((candidat) => candidat.date === date);
      const finJour = jour ? minutesOf(jour.fin) : MINUTES_PER_DAY;
      const trouvee = windowPair(ouvertures[a], ouvertures[b], finJour, effectifParDefaut);
      if (trouvee) {
        trouvees.push({ regle: null, date, fenetre: a, autreFenetre: b, ...trouvee });
      }
    }
  }
  return trouvees;
}

function windowPair(
  a: FenetreLue,
  b: FenetreLue,
  finJour: number,
  effectifParDefaut: number | null,
): Pick<FenetresChevauchantes, 'debut' | 'fin' | 'effectif' | 'autreEffectif'> | null {
  if (!isValidWindow(a) || !isValidWindow(b)) {
    return null;
  }
  const effectif = effectifOrDefault(a.effectif, effectifParDefaut);
  const autreEffectif = effectifOrDefault(b.effectif, effectifParDefaut);
  if (effectif === autreEffectif) {
    // Same headcount: the union is exactly what was meant.
    return null;
  }
  const bornes = recouvrement(a, b, finJour);
  return bornes
    ? { debut: hourOf(bornes[0]), fin: hourOf(bornes[1]), effectif, autreEffectif }
    : null;
}

/**
 * Where a rule takes over a less specific one of the opposite mode — the
 * layering working as intended (« le plus spécifique gagne »): no anomaly, but
 * a priority worth saying under the rule that wins. `dates` are the edition
 * days it does so on; empty when the edition has no day yet.
 */
export interface Priorite {
  regle: number;
  surRegle: number;
  dates: string[];
}

export function priorites(
  horaires: readonly HoraireStand[],
  jours: readonly JourEdition[],
): Priorite[] {
  const trouvees: Priorite[] = [];
  horaires.forEach((horaire, regle) => {
    if (!horaire.fenetres.some(isValidWindow)) {
      return;
    }
    horaires.forEach((autre, surRegle) => {
      if (
        regle === surRegle ||
        autre.mode === horaire.mode ||
        SPECIFICITE[autre.jours] >= SPECIFICITE[horaire.jours] ||
        !autre.fenetres.some(isValidWindow)
      ) {
        return;
      }
      if (jours.length === 0) {
        trouvees.push({ regle, surRegle, dates: [] });
        return;
      }
      const dates = jours
        .map((jour) => jour.date)
        .filter(
          (date) => couvreJour(autre, date) && reglesRetenues(horaires, date).includes(regle),
        );
      if (dates.length > 0) {
        trouvees.push({ regle, surRegle, dates });
      }
    });
  });
  return trouvees;
}

/**
 * The anomalies of the opening report about how a stand's rules are written —
 * the three above, as the backend names them (`AnomalyType.isInformational`):
 * the resolver settles them, so they are shown for information, never as an alert.
 */
export function isInformationalAnomaly(type: TypeAnomalieOuverture): boolean {
  return (
    type === 'REGLES_CHEVAUCHANTES' || type === 'REGLE_MASQUEE' || type === 'FENETRES_CHEVAUCHANTES'
  );
}

/**
 * The edition's days as the analysis reads them, from its créneaux — the days
 * the resolver resolves (`HoraireStandResolver.datesConcernees`): each date
 * carrying one, with the latest end among them (`24:00` for a créneau that
 * reaches or crosses midnight), and the morning after a créneau that crosses
 * midnight, ending where that créneau stops. Mirrors
 * `OuvertureStandsAnalyzer.eventDays`.
 */
export function joursEdition(
  creneaux: readonly { date: string; heureDebut: string; heureFin: string }[],
): JourEdition[] {
  const ends = new Map<string, number>();
  const extend = (date: string, end: number) => ends.set(date, Math.max(ends.get(date) ?? 0, end));
  for (const creneau of creneaux) {
    if (!creneau.date || !creneau.heureDebut || !creneau.heureFin) {
      continue;
    }
    const start = minutesOf(creneau.heureDebut);
    const rawEnd = minutesOf(creneau.heureFin);
    if (rawEnd > start) {
      extend(creneau.date, rawEnd);
    } else {
      extend(creneau.date, MINUTES_PER_DAY);
      const nextDay = parseDateKey(creneau.date);
      nextDay.setDate(nextDay.getDate() + 1);
      extend(toDateKey(nextDay), rawEnd);
    }
  }
  return [...ends.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, end]) => ({ date, fin: hourOf(end) }));
}

/* ------------------------- compact window syntax ------------------------- */
//
// `10:00-12:00@2, 14:00-` : the day of a stand on one line — the form MCP
// clients already send to `creer_stand_complet` (`McpArgs.fenetres`), and the
// shape a line of the organiser's own spreadsheet has. Mirrors the server
// parser: a window is `debut-fin`, an empty end runs until closing time, and
// `@N` names the seats of that one window.
//
// It accepts more than the server does, and normalises before sending: `10h`,
// `10h30`, `10.30` for the hour, `;` as well as `,` between windows, and `–`
// or `→` as well as `-` inside one. What it does not do is guess: a lone digit
// after the separator (`9:5`) is refused, not read as 9 h 50.

export type ErreurSaisieFenetres = 'VIDE' | 'FORME' | 'HEURE' | 'EFFECTIF';

/** What one compact line parses to: windows, or the first thing wrong with it. */
export type SaisieFenetres =
  | { readonly fenetres: FenetreHoraire[]; readonly erreur: null; readonly morceau: null }
  | { readonly fenetres: null; readonly erreur: ErreurSaisieFenetres; readonly morceau: string };

/**
 * `10`, `10h`, `10h30`, `9:5`, `09:30` → `HH:MM`; `null` for anything else.
 * Hours run 0-23 and minutes 0-59: a window never crosses midnight, so `24:00`
 * is not a time here — the open-ended form is how "until closing" is written.
 */
export function normaliseHour(text: string): string | null {
  // A lone digit after the separator is refused rather than guessed: `9:5`
  // reads as 9 h 50 to one person and 9 h 05 to the next, and either reading
  // silently rewrites an hour the user believes they typed.
  const m = /^(\d{1,2})(?:[h:.](\d{2})?)?$/i.exec(text.trim());
  if (!m) {
    return null;
  }
  const heures = Number(m[1]);
  const minutes = m[2] ? Number(m[2]) : 0;
  if (heures > 23 || minutes > 59) {
    return null;
  }
  return `${String(heures).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}

const LINE_TERMINATOR = /[\n\r\u2028\u2029]/;

/**
 * `10:00-12:00`, `10:00 - 12:00`, `10:00→12:00` split on their first
 * separator — which must sit after the start, so `-` inside a time is not
 * mistaken for it — into the start as typed and the end with its leading
 * blanks dropped (possibly empty: an open end). `null` without a start or a
 * separator, or when the end runs over a line break.
 */
export function splitHourRange(text: string): [debut: string, fin: string] | null {
  const index = text.search(/[-–→]/);
  if (index <= 0) {
    return null;
  }
  const fin = text.slice(index + 1).trimStart();
  return LINE_TERMINATOR.test(fin) ? null : [text.slice(0, index), fin];
}

/** One window of a compact line, `10:00-12:00@2`, or the first thing wrong with it. */
function parseFenetre(morceau: string): FenetreHoraire | Exclude<ErreurSaisieFenetres, 'VIDE'> {
  let corps = morceau;
  let effectif: number | null = null;
  const arobase = morceau.indexOf('@');
  if (arobase >= 0) {
    const valeur = morceau.slice(arobase + 1).trim();
    corps = morceau.slice(0, arobase).trim();
    if (!/^\d+$/.test(valeur) || Number(valeur) < 1) {
      return 'EFFECTIF';
    }
    effectif = Number(valeur);
  }
  const plage = splitHourRange(corps);
  if (!plage) {
    return 'FORME';
  }
  const heureDebut = normaliseHour(plage[0]);
  const endText = plage[1].trim();
  const heureFin = endText === '' ? null : normaliseHour(endText);
  if (heureDebut === null || (endText !== '' && heureFin === null)) {
    return 'HEURE';
  }
  return { heureDebut, heureFin, effectif };
}

export function parseFenetres(text: string): SaisieFenetres {
  const fenetres: FenetreHoraire[] = [];
  for (const brut of text.split(/[,;]/)) {
    const morceau = brut.trim();
    if (morceau === '') {
      continue;
    }
    const fenetre = parseFenetre(morceau);
    if (typeof fenetre === 'string') {
      return { fenetres: null, erreur: fenetre, morceau };
    }
    fenetres.push(fenetre);
  }
  if (fenetres.length === 0) {
    return { fenetres: null, erreur: 'VIDE', morceau: text.trim() };
  }
  return { fenetres, erreur: null, morceau: null };
}

/**
 * The inverse of {@link parseFenetres}: `10:00-12:00@2, 14:00-`. A window with
 * no start yet — the empty row the detail view adds — is left out rather than
 * written `-`, which this file's own parser refuses; the rule still carries it,
 * and `erreurHoraire` still says a start is missing.
 */
export function formaterFenetres(fenetres: readonly FenetreHoraire[]): string {
  return fenetres
    .filter((fenetre) => !!fenetre.heureDebut)
    .map((fenetre) => {
      const debut = heureCourte(fenetre.heureDebut);
      const fin = fenetre.heureFin ? heureCourte(fenetre.heureFin) : '';
      const effectif =
        fenetre.effectif !== null && fenetre.effectif !== undefined ? `@${fenetre.effectif}` : '';
      return `${debut}-${fin}${effectif}`;
    })
    .join(', ');
}

/**
 * Whether a rule says anything beyond "open every day on these windows" —
 * the only shape the reference event uses, and the one the form shows by
 * default; anything else unfolds the mode and day selectors on its own.
 */
export function estCasParticulier(horaire: HoraireStand): boolean {
  return horaire.mode !== 'OUVERTURE' || horaire.jours !== 'TOUS' || !!horaire.motif;
}

/** A blank rule, defaulted to the shape that covers the common case. */
export function horaireVide(): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [{ heureDebut: '', heureFin: null, effectif: null }],
    motif: null,
  };
}

/** The messages {@link erreurHoraire} answers with, worded by the caller. */
export interface MessagesErreurHoraire {
  fenetreRequise: string;
  heureDebutRequise: string;
  fenetreInversee: string;
  effectifInvalide: string;
  /** Receives the window's effectif and the stand's maximum: the two numbers that disagree. */
  effectifDepasse: (effectif: number, effectifMax: number) => string;
  joursSemaineRequis: string;
  plageRequise: string;
  datesRequises: string;
}

/**
 * Why a rule cannot be saved as entered, or `null` when it can. Mirrors
 * `ReferenceDataService#validateHoraire`, so the dialog can block the submit
 * instead of letting the backend answer 400. Messages come from the caller.
 */
export function erreurHoraire(
  horaire: HoraireStand,
  messages: MessagesErreurHoraire,
  /** The stand's declared capacity, when known: a window may not ask for more. */
  effectifMax?: number,
): string | null {
  if (horaire.fenetres.length === 0) {
    return messages.fenetreRequise;
  }
  return erreurFenetres(horaire, messages, effectifMax) ?? erreurJours(horaire, messages);
}

function erreurFenetres(
  horaire: HoraireStand,
  messages: MessagesErreurHoraire,
  effectifMax: number | undefined,
): string | null {
  for (const fenetre of horaire.fenetres) {
    if (!fenetre.heureDebut) {
      return messages.heureDebutRequise;
    }
    if (fenetre.heureFin && fenetre.heureFin <= fenetre.heureDebut) {
      return messages.fenetreInversee;
    }
    if (effectifFenetreInvalide(fenetre.effectif)) {
      return messages.effectifInvalide;
    }
    if (effectifFenetreInvalide(fenetre.effectif, effectifMax)) {
      // Past the first check, the only way left to be invalid is the capacity.
      return messages.effectifDepasse(fenetre.effectif as number, Number(effectifMax));
    }
  }
  return null;
}

function erreurJours(horaire: HoraireStand, messages: MessagesErreurHoraire): string | null {
  switch (horaire.jours) {
    case 'JOURS_SEMAINE':
      return horaire.joursSemaine.length === 0 ? messages.joursSemaineRequis : null;
    case 'PLAGE':
      return !horaire.dateDebut || !horaire.dateFin || horaire.dateFin < horaire.dateDebut
        ? messages.plageRequise
        : null;
    case 'DATES':
      return horaire.dates.length === 0 ? messages.datesRequises : null;
    case 'TOUS':
      return null;
  }
}

/**
 * Pairs of rules the backend would reject: same day selector, intersecting
 * days, opposite mode. Mirrors `ReferenceDataService#validateHoraires`.
 */
export function conflitDeMode(horaires: readonly HoraireStand[]): boolean {
  for (let i = 0; i < horaires.length; i++) {
    for (let j = i + 1; j < horaires.length; j++) {
      if (horaires[i].mode !== horaires[j].mode && joursSeChevauchent(horaires[i], horaires[j])) {
        return true;
      }
    }
  }
  return false;
}

function joursSeChevauchent(a: HoraireStand, b: HoraireStand): boolean {
  if (a.jours !== b.jours) {
    return false;
  }
  switch (a.jours) {
    case 'TOUS':
      return true;
    case 'JOURS_SEMAINE':
      return a.joursSemaine.some((jour) => b.joursSemaine.includes(jour));
    case 'PLAGE':
      return (
        (!a.dateDebut || !b.dateFin || b.dateFin >= a.dateDebut) &&
        (!b.dateDebut || !a.dateFin || a.dateFin >= b.dateDebut)
      );
    case 'DATES':
      return a.dates.some((date) => b.dates.includes(date));
  }
}

/**
 * Weekday label of the `JOURS_SEMAINE` checkboxes. Written out rather than
 * derived from `Intl`, because the locale here is the app's own (translated at
 * runtime, see AGENTS.md) and not the browser's.
 */
export function libelleJourSemaine(jour: JourSemaine): string {
  switch (jour) {
    case 'MONDAY':
      return $localize`:@@common.weekday.monday:Lundi`;
    case 'TUESDAY':
      return $localize`:@@common.weekday.tuesday:Mardi`;
    case 'WEDNESDAY':
      return $localize`:@@common.weekday.wednesday:Mercredi`;
    case 'THURSDAY':
      return $localize`:@@common.weekday.thursday:Jeudi`;
    case 'FRIDAY':
      return $localize`:@@common.weekday.friday:Vendredi`;
    case 'SATURDAY':
      return $localize`:@@common.weekday.saturday:Samedi`;
    case 'SUNDAY':
      return $localize`:@@common.weekday.sunday:Dimanche`;
  }
}

/**
 * Day label of the preview strip: `08/07` in French, `07/08` in English —
 * short enough for a dozen cells in a row, in the order the reader expects.
 */
export function libelleJour(date: string): string {
  return new Intl.DateTimeFormat(intlLocale(), { day: '2-digit', month: '2-digit' }).format(
    parseDateKey(date),
  );
}
