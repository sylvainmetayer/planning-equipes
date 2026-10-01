// The pure half of the Réalisé vs planifié page: the report turned into the
// lines, the day columns and the footer of the shared planning grid, and the
// words the cells and the detail say. Tested without rendering.

import { GapCounts, RealisedCell, RealisedOutcome, RealisedVsPlanned } from '../../core/models';
import { JourEvenement } from '../journee/journee';
import { joursGrille } from '../planning-grille/jours-grille';
import { heures, pourcentage } from './mesure';
import { intlLocale } from '../../core/locale';
import {
  CaseGrille,
  ColonneSynthese,
  JourGrille,
  LigneGrille,
  PiedGrille,
  ValeurSynthese,
} from '../planning-grille/planning-grille';

/** One stand of the grid: its cells in the columns' order, kept for the content template. */
export interface LigneRealise extends LigneGrille {
  standNom: string;
  cellules: readonly (RealisedCell | null)[];
}

/** The grid of the page: one column per counted day, one line per stand, a footer per day. */
export interface GrilleRealise {
  jours: JourGrille[];
  lignes: LigneRealise[];
  pied: PiedGrille | null;
}

/**
 * The state a cell is coloured by, the worst first: a seat left empty, then
 * an absence or a change of holder, then a seat taken out, then a seat added,
 * and a day held as announced last.
 */
export function etatCase(counts: GapCounts): string {
  if (counts.emptySeats > 0) {
    return 'realise-vide';
  }
  if (counts.absences > 0 || counts.replacements > 0) {
    return 'realise-remplace';
  }
  if (counts.removedSeats > 0) {
    return 'realise-retire';
  }
  if (counts.addedSeats > 0) {
    return 'realise-ajoute';
  }
  return 'realise-tenu';
}

/** What a cell says aloud and in its title. */
export function libelleCase(standNom: string, jour: JourGrille, counts: GapCounts): string {
  return $localize`:@@realise.case.libelle:${standNom}:stand:, ${jour.titre}:jour: : ${counts.publishedSeats}:publies: publié(s), ${counts.absences}:absences: absence(s), ${counts.replacements}:remplacements: remplacement(s), ${counts.emptySeats}:vides: resté(s) vide(s)`;
}

/** The summary columns at the right of the days. */
export function colonnesSynthese(): ColonneSynthese[] {
  return [
    { key: 'publies', label: $localize`:@@realise.col.publies:Publiés` },
    { key: 'absences', label: $localize`:@@realise.col.absences:Absences` },
    {
      key: 'tauxAbsence',
      label: $localize`:@@realise.col.tauxAbsence:Taux d'absence`,
      titre: $localize`:@@realise.col.tauxAbsence.titre:Absences rapportées aux sièges publiés`,
    },
    { key: 'remplacements', label: $localize`:@@realise.col.remplacements:Remplacements` },
    { key: 'vides', label: $localize`:@@realise.col.vides:Restés vides` },
    {
      key: 'perdues',
      label: $localize`:@@realise.col.perdues:Heures perdues`,
      titre: $localize`:@@realise.col.perdues.titre:Heures publiées que personne n'a tenues`,
    },
  ];
}

function synthese(counts: GapCounts): Partial<Record<string, ValeurSynthese>> {
  return {
    publies: { texte: String(counts.publishedSeats) },
    absences: {
      texte: String(counts.absences),
      classe: counts.absences > 0 ? 'realise-alerte' : '',
    },
    tauxAbsence: { texte: pourcentage(counts.absenceRate) },
    remplacements: { texte: String(counts.replacements) },
    vides: {
      texte: String(counts.emptySeats),
      classe: counts.emptySeats > 0 ? 'realise-alerte' : '',
    },
    perdues: { texte: heures(counts.lostMinutes) },
  };
}

/** Counters at zero: the synthesis of a stand whose only cells are on days not counted. */
const AUCUN: GapCounts = {
  publishedSeats: 0,
  keptSeats: 0,
  absences: 0,
  replacements: 0,
  emptySeats: 0,
  removedSeats: 0,
  addedSeats: 0,
  publishedMinutes: 0,
  realisedMinutes: 0,
  lostMinutes: 0,
  absenceRate: null,
  replacementRate: null,
};

/**
 * The grid: the days with a reference as columns — counted, or measured
 * against a late publication and drawn flagged, never summed —, numbered on
 * every elapsed day so a day without reference leaves its gap in the
 * numbering; the stands as lines, those the server totals in its order, then
 * those drawn only on a late day; and the footer of absences over published
 * seats per counted day. Null cells — a stand with no seat that day — are
 * drawn neutral.
 */
export function grilleRealise(report: RealisedVsPlanned): GrilleRealise {
  const jours: JourEvenement[] = [];
  const tardifs = new Set<string>();
  report.days.forEach((day, index) => {
    if (!day.counted && !day.lateReference) {
      return;
    }
    if (day.lateReference) {
      tardifs.add(day.date);
    }
    jours.push({
      jour: index + 1,
      date: day.date,
      key: day.date,
      title: day.lateReference
        ? $localize`:@@realise.jour.titreTardif:Jour ${index + 1}:numero: — ${day.date}:date: (référence tardive, non comptée)`
        : $localize`:@@realise.jour.titre:Jour ${index + 1}:numero: — ${day.date}:date:`,
    });
  });
  const colonnes = joursGrille(jours);
  const cellsByKey = new Map<string, RealisedCell>();
  for (const cell of report.cells) {
    cellsByKey.set(`${cell.standId}|${cell.date}`, cell);
  }
  const stands = report.byStand.map((stand) => ({
    id: stand.key,
    nom: stand.label,
    counts: stand.counts,
  }));
  const totalises = new Set(stands.map((stand) => stand.id));
  const horsTotaux = new Map<string, string>();
  for (const cell of report.cells) {
    if (!totalises.has(cell.standId)) {
      horsTotaux.set(cell.standId, cell.standNom);
    }
  }
  [...horsTotaux.entries()]
    .sort(([, a], [, b]) => a.localeCompare(b, intlLocale(), { sensitivity: 'base' }))
    .forEach(([id, nom]) => stands.push({ id, nom, counts: AUCUN }));
  const lignes: LigneRealise[] = stands.map((stand) => {
    const cellules = colonnes.map((jour) => cellsByKey.get(`${stand.id}|${jour.key}`) ?? null);
    const cases: CaseGrille[] = cellules.map((cellule, index) => {
      if (cellule === null) {
        return { classe: 'realise-sans', libelle: '', active: false };
      }
      const libelle = libelleCase(stand.nom, colonnes[index], cellule.counts);
      return tardifs.has(cellule.date)
        ? {
            classe: `${etatCase(cellule.counts)} realise-tardif`,
            libelle: $localize`:@@realise.case.libelleTardif:${libelle}:case: (référence tardive, non comptée)`,
            active: true,
          }
        : { classe: etatCase(cellule.counts), libelle, active: true };
    });
    return {
      id: stand.id,
      standNom: stand.nom,
      cellules,
      cases,
      synthese: synthese(stand.counts),
    };
  });
  const countsByDay = new Map(report.byDay.map((total) => [total.key, total.counts]));
  const pied: PiedGrille | null =
    lignes.length === 0
      ? null
      : {
          libelle: $localize`:@@realise.pied:Absences / publiés`,
          cases: colonnes.map((jour) => {
            if (tardifs.has(jour.key)) {
              return $localize`:@@realise.pied.tardif:non comptée`;
            }
            const counts = countsByDay.get(jour.key);
            return counts ? `${counts.absences}/${counts.publishedSeats}` : '';
          }),
          synthese: Object.fromEntries(
            Object.entries(synthese(report.event)).map(([cle, valeur]) => [
              cle,
              valeur?.texte ?? '',
            ]),
          ),
        };
  return { jours: colonnes, lignes, pied };
}

/** The days shown without a column: elapsed, and nothing was ever published for them. */
export function unreferencedDays(report: RealisedVsPlanned): string[] {
  return report.days.filter((day) => !day.counted && !day.lateReference).map((day) => day.date);
}

/** The days measured against a publication made after they started: drawn, never counted. */
export function lateReferenceDays(report: RealisedVsPlanned): string[] {
  return report.days.filter((day) => day.lateReference).map((day) => day.date);
}

/** What the detail says of a line. */
export function libelleIssue(issue: RealisedOutcome): string {
  switch (issue) {
    case 'REPLACED':
      return $localize`:@@realise.issue.remplace:Remplacé`;
    case 'EMPTIED':
      return $localize`:@@realise.issue.vide:Resté vide`;
    case 'REMOVED':
      return $localize`:@@realise.issue.retire:Retiré (consigne ou créneau supprimé)`;
    case 'HOURS_CHANGED':
      return $localize`:@@realise.issue.horaires:Horaires modifiés`;
    case 'REFILLED':
      return $localize`:@@realise.issue.repris:Reste du siège repris`;
    case 'ADDED':
      return $localize`:@@realise.issue.ajoute:Ajouté`;
  }
}
