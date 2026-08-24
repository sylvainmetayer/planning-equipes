// Resolution of a stand's recurring horaires into per-day windows, plus the
// short human summaries the stands table and the rule editor display.
//
// This mirrors the backend `HoraireStandResolver` / `Creneau#segmentsOuvertsMinutes`
// and MUST stay in sync with it: it exists so the editor can preview a rule
// live, without a round-trip per keystroke. It is deliberately the only place
// the frontend re-implements domain logic — keep any change here and there in
// the same commit.

import { parseDateKey } from './date-utils';
import {
  FenetreHoraire,
  HoraireStand,
  JourSemaine,
  ModeHoraire,
  Stand,
  TypeJoursHoraire
} from './models';

/** Same order as the backend `TypeJoursHoraire`: least specific first. */
const SPECIFICITE: Record<TypeJoursHoraire, number> = {
  TOUS: 0,
  JOURS_SEMAINE: 1,
  PLAGE: 2,
  DATES: 3
};

const JOURS_SEMAINE: JourSemaine[] = [
  'SUNDAY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY'
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
        (!horaire.dateDebut || date >= horaire.dateDebut) && (!horaire.dateFin || date <= horaire.dateFin)
      );
    case 'DATES':
      return horaire.dates.includes(date);
  }
}

function fenetreValide(fenetre: FenetreHoraire): boolean {
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
    return { date, mode: 'OUVERTURE', fenetres: trier(ouvertures), source: 'EXCEPTION' };
  }
  if (fermetures.length > 0) {
    return { date, mode: 'FERMETURE', fenetres: trier(fermetures), source: 'EXCEPTION' };
  }

  const couvrantes = (stand.horaires ?? []).filter(
    (horaire) => couvreJour(horaire, date) && horaire.fenetres.some(fenetreValide)
  );
  if (couvrantes.length === 0) {
    return { date, mode: null, fenetres: [], source: 'DEFAUT' };
  }
  const specificiteMax = Math.max(...couvrantes.map((horaire) => SPECIFICITE[horaire.jours]));
  const gagnantes = couvrantes.filter((horaire) => SPECIFICITE[horaire.jours] === specificiteMax);
  const mode: ModeHoraire = gagnantes.some((horaire) => horaire.mode === 'OUVERTURE')
    ? 'OUVERTURE'
    : 'FERMETURE';
  const fenetres = gagnantes
    .filter((horaire) => horaire.mode === mode)
    .flatMap((horaire) => horaire.fenetres.filter(fenetreValide));
  return { date, mode, fenetres: trier(fenetres), source: 'REGLE' };
}

export function resoudreHoraires(stand: Stand, dates: readonly string[]): JourResolu[] {
  return dates.map((date) => resoudreJour(stand, date));
}

function trier(fenetres: readonly FenetreHoraire[]): FenetreHoraire[] {
  return [...fenetres]
    .map((fenetre) => ({ heureDebut: fenetre.heureDebut, heureFin: fenetre.heureFin }))
    .sort((a, b) => a.heureDebut.localeCompare(b.heureDebut));
}

/** `09:00:00` → `09:00`; leaves anything already short alone. */
export function heureCourte(heure: string): string {
  return heure.length > 5 ? heure.slice(0, 5) : heure;
}

/** `10:00 → 12:00`, or `14:00 → fermeture` for an open-ended window. */
export function decrireFenetre(fenetre: FenetreHoraire, libelleFermeture: string): string {
  const fin = fenetre.heureFin ? heureCourte(fenetre.heureFin) : libelleFermeture;
  return `${heureCourte(fenetre.heureDebut)} → ${fin}`;
}

/**
 * Compact summary of a stand's horaires for the stands table — "2 fenêtres ·
 * tous les jours" reads at a glance where the raw "24" of the dated form did
 * not. Labels come from the caller so this file stays free of `$localize`.
 */
export function resumerHoraires(
  stand: Stand,
  libelles: { aucun: string; regles: (n: number) => string; exceptions: (n: number) => string }
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
    fenetres: [{ heureDebut: '', heureFin: null }],
    motif: null
  };
}

/**
 * Why a rule cannot be saved as entered, or `null` when it can. Mirrors
 * `ReferenceDataService#validateHoraire`, so the dialog can block the submit
 * instead of letting the backend answer 400. Messages come from the caller.
 */
export function erreurHoraire(
  horaire: HoraireStand,
  messages: {
    fenetreRequise: string;
    heureDebutRequise: string;
    fenetreInversee: string;
    joursSemaineRequis: string;
    plageRequise: string;
    datesRequises: string;
  }
): string | null {
  if (horaire.fenetres.length === 0) {
    return messages.fenetreRequise;
  }
  for (const fenetre of horaire.fenetres) {
    if (!fenetre.heureDebut) {
      return messages.heureDebutRequise;
    }
    if (fenetre.heureFin && fenetre.heureFin <= fenetre.heureDebut) {
      return messages.fenetreInversee;
    }
  }
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
