// The pure side of the Planning page's « Par stand » axis (issue #713): the
// stands × days grid with the names in the cells, what the former Heatmap
// counted (« 2/2 ») and the Répartition des heures summed (seat-hours to staff
// and staffed), built from the plan the page already holds. Kept out of the
// component so a thousand cells are tested without rendering one.

import { intlLocale } from '../../core/locale';
import { PosteAffectation } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { endMinutesOfDay, minutesOfDay } from '../../core/time-of-day';
import { JourEvenement } from '../journee/journee';
import { nomCourt } from '../planning-grille/jours-grille';
import {
  CaseGrille,
  ColonneSynthese,
  JourGrille,
  LigneGrille,
  PiedGrille,
} from '../planning-grille/planning-grille';

/** What a cell shows: the names, the counters (« 2/2 »), or the coverage colour alone. */
export type DensiteStand = 'noms' | 'compteurs' | 'couverture';

/** Reads the `densite` param on this axis; anything unknown is the names. */
export function readDensiteStand(value: string | null): DensiteStand {
  return value === 'compteurs' || value === 'couverture' ? value : 'noms';
}

/** The two renderings of the axis: the grid, and the treemap of the former Répartition des heures. */
export type StandView = 'grille' | 'treemap';

export function readStandView(value: string | null): StandView {
  return value === 'treemap' ? 'treemap' : 'grille';
}

/**
 * A stand on a day. Closed is not empty: a stand with no seat that day is its
 * schedule, drawn grey; a stand whose seats nobody holds is the red the
 * reader looks for.
 */
export type StatutStandJour = 'ferme' | 'vide' | 'partiel' | 'pourvu';

export interface CaseStand extends CaseGrille {
  statut: StatutStandJour;
  sieges: number;
  pourvus: number;
  /** The people holding its seats that day, `Prénom N.`, in the order of the day. */
  noms: string[];
  /** The seat the Siège panel opens on: the first empty one, else the first one; null when closed. */
  posteId: string | null;
}

export interface LigneStand extends LigneGrille {
  standId: string;
  standNom: string;
  emplacementNom: string | null;
  cases: CaseStand[];
}

export interface TableauStands {
  lignes: LigneStand[];
  pied: PiedGrille;
}

/** The summary columns, at the right of the days: what the Répartition des heures used to fold away. */
export function colonnesStand(): ColonneSynthese[] {
  return [
    {
      key: 'aPourvoir',
      label: $localize`:@@planningStand.col.aPourvoir:À pourvoir`,
      titre: $localize`:@@planningStand.col.aPourvoir.titre:Sièges à pourvoir sur l'événement`,
    },
    {
      key: 'pourvus',
      label: $localize`:@@planningStand.col.pourvus:Pourvus`,
      titre: $localize`:@@planningStand.col.pourvus.titre:Sièges tenus par quelqu'un`,
    },
    {
      key: 'taux',
      label: '%',
      titre: $localize`:@@planningStand.col.taux.titre:Part des heures-sièges tenues`,
    },
    {
      key: 'heures',
      label: $localize`:@@planningStand.col.heures:Heures`,
      titre: $localize`:@@planningStand.col.heures.titre:Heures-sièges à pourvoir sur l'événement`,
    },
  ];
}

/** What the filters of the page keep: the stands, the person, the text. */
export interface FiltresStand {
  /** The stands the stand, location and game-category filters leave; null keeps every stand. */
  standsRetenus: ReadonlySet<string> | null;
  /** An animateur id: the stands that person holds a seat on. */
  animateur: string;
  /** Free text over the stand's name and the names on its seats. */
  recherche: string;
}

interface Accumulateur {
  standNom: string;
  emplacementNom: string | null;
  parJour: Map<string, PosteAffectation[]>;
  minutesRequises: number;
  minutesPourvues: number;
}

function seatMinutes(poste: PosteAffectation): number {
  const creneau = poste.creneau!;
  const debut = minutesOfDay(poste.heureDebutEffective ?? creneau.heureDebut);
  const fin = endMinutesOfDay(poste.heureFinEffective ?? creneau.heureFin);
  return Math.max(fin - debut, 0);
}

function heures(minutes: number): string {
  return (minutes / 60).toLocaleString(intlLocale(), { maximumFractionDigits: 1 });
}

/** Floored, never rounded: 99,6 % must not read « 100 % » on a stand that is short. */
function taux(tenues: number, requises: number): string {
  return requises <= 0 ? '—' : `${Math.floor((tenues / requises) * 100)} %`;
}

function statusOf(sieges: number, pourvus: number): StatutStandJour {
  if (sieges === 0) {
    return 'ferme';
  }
  if (pourvus === 0) {
    return 'vide';
  }
  return pourvus < sieges ? 'partiel' : 'pourvu';
}

function libelleCase(
  standNom: string,
  jour: JourGrille,
  cellule: Omit<CaseStand, 'libelle' | 'classe' | 'active'>,
): string {
  switch (cellule.statut) {
    case 'ferme':
      return $localize`:@@planningStand.case.ferme:${standNom}:stand: — ${jour.titre}:jour: : fermé`;
    case 'vide':
      return $localize`:@@planningStand.case.vide:${standNom}:stand: — ${jour.titre}:jour: : ${cellule.sieges}:sieges: siège(s), personne`;
    default:
      return $localize`:@@planningStand.case.pourvue:${standNom}:stand: — ${jour.titre}:jour: : ${cellule.pourvus}:pourvus: / ${cellule.sieges}:sieges: siège(s) tenu(s) — ${cellule.noms.join(', ')}:noms:`;
  }
}

/**
 * The grid, one line per stand of the plan kept by the filters, alphabetical,
 * one cell per day in the page's order; and its footer, the day's filled seats
 * over its seats and the event's totals.
 */
export function buildTableauStands(
  postes: readonly PosteAffectation[],
  jours: readonly JourEvenement[],
  colonnes: readonly JourGrille[],
  filtres: FiltresStand,
): TableauStands {
  const byStand = new Map<string, Accumulateur>();
  const keyByDay = new Map(jours.map((jour) => [jour.jour, jour.key]));
  for (const poste of postes) {
    const stand = poste.stand;
    const creneau = poste.creneau;
    const key = creneau ? keyByDay.get(creneau.jour) : undefined;
    if (!stand || !creneau || key === undefined) {
      continue;
    }
    let acc = byStand.get(stand.id);
    if (!acc) {
      acc = {
        standNom: stand.nom || stand.id,
        emplacementNom: stand.emplacement?.nom ?? null,
        parJour: new Map(),
        minutesRequises: 0,
        minutesPourvues: 0,
      };
      byStand.set(stand.id, acc);
    }
    const daySeats = acc.parJour.get(key) ?? [];
    daySeats.push(poste);
    acc.parJour.set(key, daySeats);
    const minutes = seatMinutes(poste);
    acc.minutesRequises += minutes;
    if (poste.animateur) {
      acc.minutesPourvues += minutes;
    }
  }

  const lignes: LigneStand[] = [];
  for (const [standId, acc] of byStand) {
    if (filtres.standsRetenus && !filtres.standsRetenus.has(standId)) {
      continue;
    }
    const all = [...acc.parJour.values()].flat();
    if (filtres.animateur && !all.some((poste) => poste.animateur?.id === filtres.animateur)) {
      continue;
    }
    const noms = (poste: PosteAffectation): string =>
      poste.animateur
        ? nomCourt(poste.animateur.prenom, poste.animateur.nom, poste.animateur.id)
        : '';
    if (!correspondAuFiltre(filtres.recherche, [acc.standNom, ...all.map(noms)])) {
      continue;
    }
    let sieges = 0;
    let pourvus = 0;
    const cases = colonnes.map((jour) => {
      const daySeats = [...(acc.parJour.get(jour.key) ?? [])].sort(
        (gauche, droite) =>
          (gauche.heureDebutEffective ?? gauche.creneau!.heureDebut).localeCompare(
            droite.heureDebutEffective ?? droite.creneau!.heureDebut,
          ) || gauche.id.localeCompare(droite.id),
      );
      const tenus = daySeats.filter((poste) => poste.animateur);
      sieges += daySeats.length;
      pourvus += tenus.length;
      const base = {
        statut: statusOf(daySeats.length, tenus.length),
        sieges: daySeats.length,
        pourvus: tenus.length,
        noms: tenus.map(noms),
        posteId: (daySeats.find((poste) => !poste.animateur) ?? daySeats[0])?.id ?? null,
      };
      return {
        ...base,
        classe: `planning-stand-${base.statut}`,
        libelle: libelleCase(acc.standNom, jour, base),
        active: base.posteId !== null,
      };
    });
    lignes.push({
      id: standId,
      standId,
      standNom: acc.standNom,
      emplacementNom: acc.emplacementNom,
      cases,
      synthese: {
        aPourvoir: { texte: String(sieges) },
        pourvus: { texte: String(pourvus) },
        taux: {
          texte: taux(acc.minutesPourvues, acc.minutesRequises),
          classe: acc.minutesPourvues < acc.minutesRequises ? 'planning-stand-manque' : '',
        },
        heures: { texte: heures(acc.minutesRequises) },
      },
    });
  }
  lignes.sort(
    (gauche, droite) =>
      gauche.standNom.localeCompare(droite.standNom) ||
      gauche.standId.localeCompare(droite.standId),
  );
  return { lignes, pied: pied(lignes, byStand, colonnes.length) };
}

/** The totals of the lines on screen: a filtered grid never sums lines it does not show. */
function pied(
  lignes: readonly LigneStand[],
  byStand: ReadonlyMap<string, Accumulateur>,
  jours: number,
): PiedGrille {
  const cases = Array.from({ length: jours }, (_, index) => {
    const sieges = lignes.reduce((somme, ligne) => somme + ligne.cases[index].sieges, 0);
    const pourvus = lignes.reduce((somme, ligne) => somme + ligne.cases[index].pourvus, 0);
    return sieges === 0 ? '—' : `${pourvus}/${sieges}`;
  });
  let sieges = 0;
  let pourvus = 0;
  let requises = 0;
  let tenues = 0;
  for (const ligne of lignes) {
    for (const cellule of ligne.cases) {
      sieges += cellule.sieges;
      pourvus += cellule.pourvus;
    }
    const acc = byStand.get(ligne.standId);
    requises += acc?.minutesRequises ?? 0;
    tenues += acc?.minutesPourvues ?? 0;
  }
  return {
    libelle: $localize`:@@planningStand.pied:Total du jour`,
    cases,
    synthese: {
      aPourvoir: String(sieges),
      pourvus: String(pourvus),
      taux: taux(tenues, requises),
      heures: heures(requises),
    },
  };
}
