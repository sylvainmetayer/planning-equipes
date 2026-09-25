// The « Comparer » view of the Ouvertures page: a few chosen stands laid on the
// same days and the same columns, each cell measured against a reference stand.
//
// Pure, like the rest of this folder: what counts as a difference is decided
// here and tested without rendering. It reads the report the solver reads
// (`RapportOuvertures`), never the rules as typed — two stands whose schedules
// are written differently but open the same way (a rule on one, the equivalent
// dated exceptions on the other) have nothing to harmonise, and comparing the
// typed input would flag them anyway. Which layer decided a cell (`source`) is
// therefore deliberately not compared.

import {
  CelluleCreneauOuverture,
  ColonneCreneau,
  HoraireStand,
  IndisponibiliteStand,
  LigneStandOuverture,
  OuvertureStand,
  RapportOuvertures,
  SegmentCellule,
  Stand,
} from '../../core/models';
import { formatHeure } from '../../core/time-of-day';

/** The fewest and the most stands a comparison takes. */
export const MIN_STANDS_COMPARES = 2;
export const MAX_STANDS_COMPARES = 8;

/**
 * How a cell differs from the reference's, most telling first: open against
 * closed says everything, and then neither hours nor headcount are compared.
 */
export type TypeEcart = 'OUVERTURE' | 'HEURES' | 'EFFECTIF';

/** One stand in one column. */
export interface CaseComparee {
  standId: string;
  nom: string;
  reference: boolean;
  /** Closed when null. */
  effectif: number | null;
  /** What the cell prints: the headcount, the stretches of a partial cell, or « — » when closed. */
  texte: string;
  ecarts: TypeEcart[];
  /** The difference in words, empty when there is none. */
  description: string;
}

export interface ColonneComparee {
  cle: string;
  /** `14:00 – 18:00`. */
  libelle: string;
  cases: CaseComparee[];
  ecart: boolean;
}

export interface JourCompare {
  date: string;
  jour: number;
  colonnes: ColonneComparee[];
  /** True as soon as one stand differs from the reference somewhere on the day. */
  ecart: boolean;
}

/** One stand of the synthesis: how many days it differs on, and the first difference in words. */
export interface SyntheseStandComparee {
  standId: string;
  nom: string;
  joursEnEcart: number;
  premierEcart: string | null;
}

export interface Comparaison {
  /** The reference actually used: the one asked for when it is compared, else the first stand. */
  referenceId: string | null;
  stands: { standId: string; nom: string }[];
  jours: JourCompare[];
  /** The stands other than the reference, in selection order. */
  synthese: SyntheseStandComparee[];
  /** Ids asked for that the report does not know — a stand deleted since the link was made. */
  inconnus: string[];
}

/**
 * The comparison of `standIds` against `referenceId`. Unknown ids are set
 * aside rather than failing the view, duplicates are kept once, and a
 * reference that is not among the stands compared hands the role to the
 * first of them.
 */
export function comparer(
  rapport: RapportOuvertures | null,
  standIds: readonly string[],
  referenceId: string | null,
): Comparaison {
  const lignesParId = new Map((rapport?.stands ?? []).map((ligne) => [ligne.standId, ligne]));
  const uniques = Array.from(new Set(standIds));
  const lignes = uniques
    .map((id) => lignesParId.get(id))
    .filter((ligne): ligne is LigneStandOuverture => ligne !== undefined);
  const inconnus = rapport ? uniques.filter((id) => !lignesParId.has(id)) : [];
  const reference = lignes.find((ligne) => ligne.standId === referenceId) ?? lignes[0] ?? null;
  const stands = lignes.map((ligne) => ({ standId: ligne.standId, nom: nomDe(ligne) }));
  if (!rapport || !reference) {
    return { referenceId: null, stands, jours: [], synthese: [], inconnus };
  }

  const jours: JourCompare[] = rapport.jours.map((jour) => {
    const colonnes = jour.creneaux.map((colonne) => {
      const cellules = lignes.map((ligne) => lireCellule(ligne, jour.date, colonne));
      const celluleReference = lireCellule(reference, jour.date, colonne);
      const cases = lignes.map((ligne, index) =>
        caseComparee(ligne, ligne === reference, cellules[index], celluleReference, colonne),
      );
      return {
        cle: `${colonne.id}-${colonne.tranche}`,
        libelle: `${formatHeure(colonne.heureDebut)} – ${formatHeure(colonne.heureFin)}`,
        cases,
        ecart: cases.some((each) => each.ecarts.length > 0),
      };
    });
    return {
      date: jour.date,
      jour: jour.jour,
      colonnes,
      ecart: colonnes.some((colonne) => colonne.ecart),
    };
  });

  const synthese = lignes
    .filter((ligne) => ligne !== reference)
    .map((ligne) => {
      let joursEnEcart = 0;
      let premierEcart: string | null = null;
      jours.forEach((jour) => {
        let ecartDuJour = false;
        jour.colonnes.forEach((colonne) => {
          const cellule = colonne.cases.find((each) => each.standId === ligne.standId)!;
          if (cellule.ecarts.length === 0) {
            return;
          }
          ecartDuJour = true;
          premierEcart ??= $localize`:@@ouvertures.comparer.premierEcart:le ${jour.date}:date: de ${colonne.libelle}:colonne:, ${cellule.description}:ecart:`;
        });
        if (ecartDuJour) {
          joursEnEcart++;
        }
      });
      return { standId: ligne.standId, nom: nomDe(ligne), joursEnEcart, premierEcart };
    });

  return { referenceId: reference.standId, stands, jours, synthese, inconnus };
}

function nomDe(ligne: LigneStandOuverture): string {
  return ligne.nom || ligne.standId;
}

/** A stand's cell in one column; a cell the report does not carry is a closed one. */
function lireCellule(
  ligne: LigneStandOuverture,
  date: string,
  colonne: ColonneCreneau,
): CelluleCreneauOuverture | null {
  return (
    ligne.jours
      .find((jour) => jour.date === date)
      ?.creneaux.find(
        (cellule) => cellule.creneauId === colonne.id && cellule.tranche === colonne.tranche,
      ) ?? null
  );
}

/**
 * The open stretches of a cell, empty when closed. A cell open over its whole
 * column may carry no segment at all: it is then one stretch, the column.
 */
function segmentsDe(
  cellule: CelluleCreneauOuverture | null,
  colonne: ColonneCreneau,
): SegmentCellule[] {
  if (!cellule || cellule.effectif === null) {
    return [];
  }
  if (cellule.segments.length > 0) {
    return cellule.segments;
  }
  return [
    { heureDebut: colonne.heureDebut, heureFin: colonne.heureFin, effectif: cellule.effectif },
  ];
}

function plage(segment: SegmentCellule): string {
  return `${formatHeure(segment.heureDebut)}–${formatHeure(segment.heureFin)}`;
}

function heures(segments: SegmentCellule[]): string {
  return segments.map(plage).join(', ');
}

function effectifs(segments: SegmentCellule[]): string {
  return segments.map((segment) => segment.effectif).join(', ');
}

function texteCellule(cellule: CelluleCreneauOuverture | null, colonne: ColonneCreneau): string {
  if (!cellule || cellule.effectif === null) {
    return '—';
  }
  if (!cellule.partiel) {
    return String(cellule.effectif);
  }
  return segmentsDe(cellule, colonne)
    .map((segment) => `${plage(segment)} ×${segment.effectif}`)
    .join(' ; ');
}

function caseComparee(
  ligne: LigneStandOuverture,
  reference: boolean,
  cellule: CelluleCreneauOuverture | null,
  celluleReference: CelluleCreneauOuverture | null,
  colonne: ColonneCreneau,
): CaseComparee {
  const base = {
    standId: ligne.standId,
    nom: nomDe(ligne),
    reference,
    effectif: cellule?.effectif ?? null,
    texte: texteCellule(cellule, colonne),
  };
  if (reference) {
    return { ...base, ecarts: [], description: '' };
  }
  const { ecarts, description } = ecartsEntre(
    segmentsDe(cellule, colonne),
    segmentsDe(celluleReference, colonne),
  );
  return { ...base, ecarts, description };
}

/** What separates a cell from the reference's, and the sentence that says it. */
export function ecartsEntre(
  segments: SegmentCellule[],
  reference: SegmentCellule[],
): { ecarts: TypeEcart[]; description: string } {
  const ouvert = segments.length > 0;
  const referenceOuverte = reference.length > 0;
  if (ouvert !== referenceOuverte) {
    return {
      ecarts: ['OUVERTURE'],
      description: ouvert
        ? $localize`:@@ouvertures.comparer.ecart.ouvert:ouvert ${heures(segments)}:heures: là où la référence est fermée`
        : $localize`:@@ouvertures.comparer.ecart.ferme:fermé là où la référence ouvre ${heures(reference)}:heures:`,
    };
  }
  if (!ouvert) {
    return { ecarts: [], description: '' };
  }
  const ecarts: TypeEcart[] = [];
  const morceaux: string[] = [];
  if (heures(segments) !== heures(reference)) {
    ecarts.push('HEURES');
    morceaux.push(
      $localize`:@@ouvertures.comparer.ecart.heures:ouvert ${heures(segments)}:heures: au lieu de ${heures(reference)}:reference:`,
    );
  }
  if (effectifs(segments) !== effectifs(reference)) {
    ecarts.push('EFFECTIF');
    morceaux.push(
      $localize`:@@ouvertures.comparer.ecart.effectif:effectif ${effectifs(segments)}:effectif: au lieu de ${effectifs(reference)}:reference:`,
    );
  }
  return { ecarts, description: morceaux.join(' ; ') };
}

/* ------------------------- the rules side by side ------------------------- */

/**
 * What a recurring rule *is*, for telling whether two stands carry the same
 * one: its mode, its days and its windows — not its id, which differs from one
 * stand to the next, nor its reason, which is free text.
 */
export function cleRegle(horaire: HoraireStand): string {
  const jours = (() => {
    switch (horaire.jours) {
      case 'JOURS_SEMAINE':
        return [...(horaire.joursSemaine ?? [])].sort((a, b) => a.localeCompare(b)).join(',');
      case 'PLAGE':
        return `${horaire.dateDebut ?? ''}..${horaire.dateFin ?? ''}`;
      case 'DATES':
        return [...(horaire.dates ?? [])].sort((a, b) => a.localeCompare(b)).join(',');
      default:
        return '';
    }
  })();
  const fenetres = (horaire.fenetres ?? [])
    .map(
      (fenetre) =>
        `${formatHeure(fenetre.heureDebut)}-${fenetre.heureFin ? formatHeure(fenetre.heureFin) : ''}x${fenetre.effectif ?? ''}`,
    )
    .sort((a, b) => a.localeCompare(b))
    .join('|');
  return `${horaire.mode}/${horaire.jours}/${jours}/${fenetres}`;
}

/** One rule of one stand, and whether the reference carries it too. */
export interface RegleComparee {
  horaire: HoraireStand;
  /** False on a stand other than the reference whose rule the reference does not have. */
  chezReference: boolean;
}

/** One dated exception of one stand. */
export interface ExceptionComparee {
  date: string;
  ouverture: boolean;
  exception: OuvertureStand | IndisponibiliteStand;
}

/** One stand's column of the rules panel. */
export interface ReglesStand {
  standId: string;
  nom: string;
  reference: boolean;
  regles: RegleComparee[];
  /** Rules of the reference this stand does not carry — what the copy would bring. */
  reglesManquantes: HoraireStand[];
  /** Its dated exceptions, by date. */
  exceptions: ExceptionComparee[];
}

/**
 * The rules and dated exceptions of every compared stand, side by side. A
 * stand missing from the referential (not loaded, or deleted) is left out:
 * the grid above still compares what the report holds.
 */
export function reglesComparees(
  stands: readonly Stand[],
  standIds: readonly string[],
  referenceId: string | null,
): ReglesStand[] {
  const parId = new Map(stands.map((stand) => [stand.id, stand]));
  const reference = referenceId ? parId.get(referenceId) : undefined;
  const clesReference = new Set((reference?.horaires ?? []).map(cleRegle));
  return standIds
    .map((id) => parId.get(id))
    .filter((stand): stand is Stand => stand !== undefined)
    .map((stand) => {
      const estReference = stand.id === referenceId;
      const cles = new Set((stand.horaires ?? []).map(cleRegle));
      const exceptions: ExceptionComparee[] = [
        ...(stand.ouvertures ?? []).map((exception) => ({
          date: exception.date,
          ouverture: true,
          exception,
        })),
        ...(stand.indisponibilites ?? []).map((exception) => ({
          date: exception.date,
          ouverture: false,
          exception,
        })),
      ].sort(
        (left, right) =>
          left.date.localeCompare(right.date) ||
          left.exception.heureDebut.localeCompare(right.exception.heureDebut),
      );
      return {
        standId: stand.id,
        nom: stand.nom || stand.id,
        reference: estReference,
        regles: (stand.horaires ?? []).map((horaire) => ({
          horaire,
          chezReference: estReference || clesReference.has(cleRegle(horaire)),
        })),
        reglesManquantes: estReference
          ? []
          : (reference?.horaires ?? []).filter((horaire) => !cles.has(cleRegle(horaire))),
        exceptions,
      };
    });
}

/* ------------------------------ the address ------------------------------ */

/** The `stands` query param: ids separated by commas, blanks and duplicates dropped, at most eight. */
export function readStandsParam(value: string | null): string[] {
  const ids = (value ?? '')
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id.length > 0);
  return Array.from(new Set(ids)).slice(0, MAX_STANDS_COMPARES);
}

/** The ids back into the param; absent when there are none. */
export function writeStandsParam(ids: readonly string[]): string | null {
  return ids.length > 0 ? ids.join(',') : null;
}

/**
 * Stands to add to a selection — one picked by name, or all of a game
 * category's — capped at eight. Returns the new selection and how many had to
 * be left out, which the view says rather than dropping them silently.
 */
export function ajouterStands(
  selection: readonly string[],
  ajouts: readonly string[],
): { selection: string[]; refuses: number } {
  const resultat = [...selection];
  let refuses = 0;
  ajouts.forEach((id) => {
    if (resultat.includes(id)) {
      return;
    }
    if (resultat.length >= MAX_STANDS_COMPARES) {
      refuses++;
      return;
    }
    resultat.push(id);
  });
  return { selection: resultat, refuses };
}
