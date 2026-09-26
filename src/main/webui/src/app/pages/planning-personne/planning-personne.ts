// The pure side of the Planning page's « Par personne » axis (issue #713): the
// animateurs × days grid with the stand and the hours in the cells, coloured
// work / rest / unavailable as the Jours de repos grid did (`pages/repos`, whose
// builder this reads), and at the right the columns of the Équité report and
// the two payroll counters of the Heures report the Équité did not carry,
// joined person by person. Kept out of the component so a hundred and fifty
// lines are tested without rendering one.

import { intlLocale } from '../../core/locale';
import {
  HeuresAnimateur,
  HeuresRapport,
  LigneEquite,
  PlanningEvenement,
  PosteAffectation,
  RapportEquite,
} from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { endMinutesOfDay, formatHeure, minutesOfDay } from '../../core/time-of-day';
import { SortState } from '../../core/view-query-params';
import {
  COLUMNS_AFTER_WEEKS,
  columnConstraint,
  formatColonne,
  libelleColonne,
  libelleSolveur,
} from '../equite/equite';
import { JourEvenement } from '../journee/journee';
import {
  TRI_NOM,
  CaseGrille,
  ColonneSynthese,
  LigneGrille,
  PiedGrille,
} from '../planning-grille/planning-grille';
import { buildTableauRepos, LigneRepos, StatutJour, totauxParJour } from '../repos/repos';

/** What a cell shows: the stand and the hours, or the colour alone. */
export type DensitePersonne = 'detail' | 'compact';

/**
 * Reads the `densite` param on this axis. `compact` is the colour alone;
 * anything else is the detail — `confort`, the Jours de repos grid's « with
 * the hours », included, so its bookmarks land on what they asked for.
 */
export function readDensitePersonne(value: string | null): DensitePersonne {
  return value === 'compact' ? 'compact' : 'detail';
}

/** The grid, or the frise: one proportional bar per person, a month on one screen. */
export type PersonView = 'grille' | 'frise';

export function readPersonView(value: string | null): PersonView {
  return value === 'frise' ? 'frise' : 'grille';
}

/** The summary column of the distance to the median of the hours. */
export const MEDIAN_GAP = 'ecartMediane';
/** The two payroll counters the Heures report adds to the Équité ones. */
export const HEURES_DIMANCHE = 'heuresDimanche';
export const NUIT_PAIE = 'heuresNuit';

/** The summary columns, in the order of the table: the hours, their distance, the weeks, then the rest. */
export function summaryKeys(equite: RapportEquite | null): string[] {
  const [soiree, weekEnd, ferie, ...reste] = COLUMNS_AFTER_WEEKS;
  return [
    'heuresTotal',
    MEDIAN_GAP,
    ...(equite?.semaines ?? []),
    soiree,
    weekEnd,
    ferie,
    HEURES_DIMANCHE,
    NUIT_PAIE,
    ...reste,
  ];
}

/** A summary column's title, as its header shows it. */
export function libelleSynthese(cle: string): string {
  switch (cle) {
    case MEDIAN_GAP:
      return $localize`:@@planningPersonne.col.ecart:Écart méd.`;
    case HEURES_DIMANCHE:
      return $localize`:@@planningPersonne.col.dimanche:Dimanche`;
    case NUIT_PAIE:
      return $localize`:@@planningPersonne.col.nuitPaie:Nuit (paie)`;
    case 'postesPenibles':
      return $localize`:@@planningPersonne.col.exigeants:Postes exigeants`;
    default:
      return libelleColonne(cle);
  }
}

/** What a header's title adds: the payroll bound, the evening, the rule the solver measures it with. */
function titreSynthese(cle: string, equite: RapportEquite | null): string {
  switch (cle) {
    case MEDIAN_GAP:
      return $localize`:@@planningPersonne.col.ecart.titre:Heures de la personne moins la médiane des heures`;
    case HEURES_DIMANCHE:
      return $localize`:@@planningPersonne.col.dimanche.titre:Heures un dimanche, pour la paie`;
    case NUIT_PAIE:
      return $localize`:@@planningPersonne.col.nuitPaie.titre:Heures après 22 h, pour la paie : une borne fixe, qui n'est pas la soirée`;
    case 'heuresSoiree': {
      const heure = (equite?.heureDebutSoiree ?? '').slice(0, 5);
      return $localize`:@@planningPersonne.col.soiree.titre:Heures après ${heure}:heure:, le début de soirée réglé dans les paramètres`;
    }
    default:
      return libelleSolveur(columnConstraint(equite, cle));
  }
}

/** The value of one person in one summary column; null when the reports know nothing of them there. */
function valeur(
  cle: string,
  equite: LigneEquite | undefined,
  heures: HeuresAnimateur | undefined,
  medianeHeures: number | null,
): number | null {
  if (cle === HEURES_DIMANCHE) {
    return heures ? heures.heuresDimanche : null;
  }
  if (cle === NUIT_PAIE) {
    return heures ? heures.heuresNuit : null;
  }
  if (!equite) {
    return null;
  }
  if (cle === MEDIAN_GAP) {
    return medianeHeures === null ? null : equite.heuresTotal - medianeHeures;
  }
  if (/^\d{4}-W\d{2}$/.test(cle)) {
    return equite.heuresParSemaine[cle] ?? 0;
  }
  const brute = equite[cle as keyof LigneEquite];
  return typeof brute === 'number' ? brute : null;
}

/** Hours, a count or a rate, the way the Équité screen wrote them; a signed distance for the median column. */
function formatValue(cle: string, amount: number | null): string {
  if (amount === null) {
    return '—';
  }
  const locale = intlLocale();
  if (cle === MEDIAN_GAP) {
    const signe = amount > 0.05 ? '+' : '';
    return `${signe}${amount.toLocaleString(locale, { maximumFractionDigits: 1 })}`;
  }
  const format = cle === HEURES_DIMANCHE || cle === NUIT_PAIE ? 'heures' : formatColonne(cle);
  switch (format) {
    case 'heures':
      return amount.toLocaleString(locale, { maximumFractionDigits: 1 });
    case 'taux':
      return amount.toLocaleString(locale, { style: 'percent', maximumFractionDigits: 0 });
    default:
      return String(amount);
  }
}

/** Below this, a distance to the median is noise, not a colour. */
const NEGLIGIBLE_GAP = 0.05;

/** Above the median in red, below in the « positive » colour: the reading of the Équité screen. */
function gapClass(amount: number | null): string {
  if (amount === null || Math.abs(amount) < NEGLIGIBLE_GAP) {
    return '';
  }
  return amount > 0 ? 'planning-personne-au-dessus' : 'planning-personne-en-dessous';
}

/** One day of one person: the stands and the windows held, and the seat a click opens. */
interface JourneePersonne {
  stands: string[];
  fenetres: string[];
  posteId: string;
  /** A window running past the start of the evening. */
  soiree: boolean;
}

export interface CasePersonne extends CaseGrille {
  statut: StatutJour;
  /** `Tir, Dixit` — the stands held that day; empty on a day without a seat. */
  stands: string;
  /** `09:00–12:00, 18:00–22:00`. */
  heures: string;
  posteId: string | null;
}

export interface LignePersonne extends LigneGrille {
  animateurId: string;
  nom: string;
  cases: CasePersonne[];
  repos: LigneRepos;
  /** The summary values, to sort by. */
  valeurs: Readonly<Record<string, number | null>>;
}

export interface TableauPersonnes {
  lignes: LignePersonne[];
  colonnes: ColonneSynthese[];
  /** The columns no line has a value in but zero: hidden unless asked for. */
  vides: string[];
  pied: PiedGrille;
}

/** What the filters of the page keep. */
export interface FiltresPersonne {
  animateur: string;
  /** The stands the stand, location and game-category filters leave: the people holding a seat on one. */
  standsRetenus: ReadonlySet<string> | null;
  recherche: string;
  sansReposSeulement: boolean;
  /** Shows the columns every line leaves at zero. */
  toutesColonnes: boolean;
  tri: SortState;
}

function detailsByDay(
  postes: readonly PosteAffectation[],
  debutSoiree: number | null,
): Map<string, Map<number, JourneePersonne>> {
  const byPerson = new Map<string, Map<number, JourneePersonne>>();
  const tries = [...postes].sort(
    (gauche, droite) =>
      (gauche.heureDebutEffective ?? gauche.creneau?.heureDebut ?? '').localeCompare(
        droite.heureDebutEffective ?? droite.creneau?.heureDebut ?? '',
      ) || gauche.id.localeCompare(droite.id),
  );
  for (const poste of tries) {
    const animateur = poste.animateur;
    const creneau = poste.creneau;
    if (!animateur || !creneau) {
      continue;
    }
    let byDay = byPerson.get(animateur.id);
    if (!byDay) {
      byDay = new Map();
      byPerson.set(animateur.id, byDay);
    }
    const debut = poste.heureDebutEffective ?? creneau.heureDebut;
    const fin = poste.heureFinEffective ?? creneau.heureFin;
    const journee = byDay.get(creneau.jour) ?? {
      stands: [],
      fenetres: [],
      posteId: poste.id,
      soiree: false,
    };
    const stand = poste.stand?.nom || poste.stand?.id || '';
    if (stand && !journee.stands.includes(stand)) {
      journee.stands.push(stand);
    }
    journee.fenetres.push(`${formatHeure(debut)}–${formatHeure(fin)}`);
    if (debutSoiree !== null && endMinutesOfDay(fin) > Math.max(debutSoiree, minutesOfDay(debut))) {
      journee.soiree = true;
    }
    byDay.set(creneau.jour, journee);
  }
  return byPerson;
}

function comparer(gauche: LignePersonne, droite: LignePersonne, tri: SortState): number {
  if (tri.active === TRI_NOM) {
    return gauche.nom.localeCompare(droite.nom);
  }
  // A person the reports know nothing of sorts below everybody, whichever way.
  const a = gauche.valeurs[tri.active] ?? Number.NEGATIVE_INFINITY;
  const b = droite.valeurs[tri.active] ?? Number.NEGATIVE_INFINITY;
  return a - b;
}

/**
 * The grid: one line per animateur of the plan the filters keep, in the Jours
 * de repos order (longest run of worked days first) or in the order a column
 * asks for; one cell per day of the page; the Équité and payroll columns at
 * the right, the always-empty ones listed apart; the footer counting who rests
 * each day, over the lines on screen.
 */
export function buildTableauPersonnes(
  planning: PlanningEvenement | null,
  jours: readonly JourEvenement[],
  equite: RapportEquite | null,
  heures: HeuresRapport | null,
  filtres: FiltresPersonne,
): TableauPersonnes {
  const postes = planning?.postes ?? [];
  const repos = buildTableauRepos(
    postes,
    planning?.animateurs ?? [],
    planning?.contraintesAdHoc ?? [],
  );
  const debutSoiree = equite?.heureDebutSoiree ? minutesOfDay(equite.heureDebutSoiree) : null;
  const details = detailsByDay(postes, debutSoiree);
  const equityById = new Map((equite?.lignes ?? []).map((ligne) => [ligne.animateurId, ligne]));
  const hoursById = new Map((heures?.animateurs ?? []).map((ligne) => [ligne.animateurId, ligne]));
  const medianeHeures = equite?.syntheses?.['heuresTotal']?.mediane ?? null;
  const keys = summaryKeys(equite);
  const retenus = filtres.standsRetenus;
  const standsOf = new Map<string, Set<string>>();
  if (retenus) {
    for (const poste of postes) {
      if (poste.animateur && poste.stand) {
        const ensemble = standsOf.get(poste.animateur.id) ?? new Set<string>();
        ensemble.add(poste.stand.id);
        standsOf.set(poste.animateur.id, ensemble);
      }
    }
  }

  const dayColumn = new Map(repos.jours.map((colonne, index) => [colonne.jour, index]));
  const lignes: LignePersonne[] = [];
  for (const ligneRepos of repos.lignes) {
    const id = ligneRepos.animateurId;
    if (filtres.animateur && id !== filtres.animateur) {
      continue;
    }
    if (retenus && ![...(standsOf.get(id) ?? [])].some((stand) => retenus.has(stand))) {
      continue;
    }
    if (filtres.sansReposSeulement && !ligneRepos.sansRepos) {
      continue;
    }
    const byDay = details.get(id) ?? new Map<number, JourneePersonne>();
    const personStands = [...byDay.values()].flatMap((journee) => journee.stands);
    if (!correspondAuFiltre(filtres.recherche, [ligneRepos.nom, id, ...personStands])) {
      continue;
    }
    const cases = jours.map((jour): CasePersonne => {
      const cellule = ligneRepos.cellules[dayColumn.get(jour.jour) ?? -1];
      const journee = byDay.get(jour.jour);
      const statut = cellule?.statut ?? 'repos';
      const classes = [`planning-personne-${statut}`];
      if (cellule?.conflit) {
        classes.push('planning-personne-conflit');
      }
      if (journee?.soiree) {
        classes.push('planning-personne-soiree');
      }
      const stands = journee?.stands.join(', ') ?? '';
      const dayHours = journee?.fenetres.join(', ') ?? '';
      return {
        statut,
        stands,
        heures: dayHours,
        posteId: journee?.posteId ?? null,
        classe: classes.join(' '),
        libelle: journee
          ? `${cellule?.tooltip ?? jour.title} — ${stands} ${dayHours}`
          : (cellule?.tooltip ?? jour.title),
        active: journee !== undefined,
      };
    });
    const ligneEquite = equityById.get(id);
    const ligneHeures = hoursById.get(id);
    const gap = gapClass(valeur(MEDIAN_GAP, ligneEquite, ligneHeures, medianeHeures));
    const valeurs: Record<string, number | null> = {};
    const synthese: Record<string, { texte: string; classe?: string }> = {};
    for (const key of keys) {
      const amount = valeur(key, ligneEquite, ligneHeures, medianeHeures);
      valeurs[key] = amount;
      synthese[key] = {
        texte: formatValue(key, amount),
        classe: key === MEDIAN_GAP || key === 'heuresTotal' ? gap : '',
      };
    }
    lignes.push({
      id,
      animateurId: id,
      nom: ligneRepos.nom,
      cases,
      repos: ligneRepos,
      valeurs,
      synthese,
      classe: ligneRepos.sansRepos ? 'planning-personne-sans-repos' : '',
    });
  }

  const tri = filtres.tri;
  const triees =
    tri.active && tri.direction
      ? [...lignes].sort((a, b) => (tri.direction === 'asc' ? 1 : -1) * comparer(a, b, tri))
      : lignes;

  // Empty over the whole plan, never over the lines a filter kept: the column
  // is hidden because the edition has nothing to say there, not because the
  // one person on screen happens to be at zero.
  const vides = keys.filter(
    (cle) => cle !== MEDIAN_GAP && lignes.every((ligne) => !ligne.valeurs[cle]),
  );
  const cachees = filtres.toutesColonnes ? new Set<string>() : new Set(vides);
  const colonnes = keys
    .filter((cle) => !cachees.has(cle))
    .map((cle) => ({ key: cle, label: libelleSynthese(cle), titre: titreSynthese(cle, equite) }));

  const totaux = totauxParJour(
    repos.jours,
    triees.map((ligne) => ligne.repos),
  );
  return {
    lignes: triees,
    colonnes,
    vides,
    pied: {
      libelle: $localize`:@@repos.footer.label:Au repos ce jour-là`,
      cases: jours.map((jour) => String(totaux[dayColumn.get(jour.jour) ?? -1]?.repos ?? '')),
      synthese: {},
    },
  };
}
