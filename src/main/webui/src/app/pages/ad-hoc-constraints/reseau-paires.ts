// The « Réseau » reading of the manual adjustments: who is tied to whom by an
// AFFINITE or an INCOMPATIBILITE, as a graph — pure, so the grouping and the
// drawing are tested without rendering (the pattern of `solver/score-curve.ts`).
//
// Deliberately not a force simulation: clusters sit on a grid, the members of
// a cluster on a circle in alphabetical order. The same data always gives the
// same picture — no jitter from one reload to the next, and a layout a test can
// compare for equality.

import { Animateur, ContrainteAdHoc } from '../../core/models';

/** The two adjustment types that relate two people; the other two target a seat. */
export type PairType = 'AFFINITE' | 'INCOMPATIBILITE';

export function isPairType(type: string): type is PairType {
  return type === 'AFFINITE' || type === 'INCOMPATIBILITE';
}

/** One person cited by at least one pair. */
export interface NoeudReseau {
  id: string;
  /** The name shown elsewhere in the admin; the id itself for an animateur no longer in the referential. */
  label: string;
  /** True when the id names nobody any more (a deleted fiche, an old import). */
  inconnu: boolean;
  affinites: number;
  incompatibilites: number;
  /** The 1-based number of the affinity cluster holding the person; null when only incompatibilities tie them. */
  grappe: number | null;
}

/**
 * One edge: one pair of people under one type. Two adjustments naming the same
 * pair under the same type, in either order, are one edge carrying both.
 */
export interface AreteReseau {
  /** `TYPE:a|b`, the two ids in order — stable, and what the view tracks. */
  key: string;
  type: PairType;
  source: string;
  target: string;
  contraintes: ContrainteAdHoc[];
  /** True when one of its adjustments is narrowed to a timeslot or a stand: the edge carries a marker. */
  restreinte: boolean;
  /** An incompatibility between two members of the same affinity cluster, unless the pair is {@link doublee}. */
  interne: boolean;
  /** The same pair also declared under the other type — refused at entry today, possible in older data. */
  doublee: boolean;
}

export interface GrappeReseau {
  /** 1-based, in drawing order: the largest cluster first. */
  numero: number;
  /** Member ids, alphabetical by label. */
  membres: string[];
}

export interface Reseau {
  noeuds: NoeudReseau[];
  aretes: AreteReseau[];
  grappes: GrappeReseau[];
  /** People tied by incompatibilities only, alphabetical by label. */
  isoles: string[];
  incompatibilitesInternes: AreteReseau[];
  /** Animateurs of the referential cited by no pair at all: counted, not listed — it is nearly everyone. */
  sansPaire: number;
}

function nomAffiche(animateur: Animateur): string {
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id;
}

/**
 * Alphabetical by label, then by id. The locale is pinned: the order fixes
 * the layout, and a browser's default locale must not move a node.
 */
function byLabel(labels: Map<string, string>): (gauche: string, droite: string) => number {
  return (gauche, droite) =>
    (labels.get(gauche) ?? gauche).localeCompare(labels.get(droite) ?? droite, 'fr') ||
    gauche.localeCompare(droite, 'fr');
}

/** Union-find over the person ids, with path halving: the clusters are its classes. */
class Partition {
  private readonly parent = new Map<string, string>();

  find(id: string): string {
    let courant = id;
    let parent = this.parent.get(courant) ?? courant;
    while (parent !== courant) {
      const grandParent = this.parent.get(parent) ?? parent;
      this.parent.set(courant, grandParent);
      courant = parent;
      parent = grandParent;
    }
    return courant;
  }

  union(gauche: string, droite: string): void {
    const racineGauche = this.find(gauche);
    const racineDroite = this.find(droite);
    if (racineGauche !== racineDroite) {
      // Deterministic: the smaller id becomes the root, whatever the order of the input.
      if (racineGauche < racineDroite) {
        this.parent.set(racineDroite, racineGauche);
      } else {
        this.parent.set(racineGauche, racineDroite);
      }
    }
  }
}

/**
 * The network of pairs. Only the first two animateurs of an adjustment count,
 * as for the solver (`AdHocConstraints#coversIdentifiablePair`); one naming
 * fewer than two distinct people is left out of the drawing, never an error.
 */
export function buildNetwork(
  contraintes: readonly ContrainteAdHoc[],
  animateurs: readonly Animateur[],
): Reseau {
  const referentiel = new Map(animateurs.map((animateur) => [animateur.id, animateur]));
  const aretes = collectEdges(contraintes);
  const { labels, compteurs, partition } = readEndpoints(aretes, referentiel);

  const ordre = byLabel(labels);
  const { grappes, isoles } = groupClusters(compteurs, partition, ordre);
  const clusterOf = new Map<string, number>();
  for (const grappe of grappes) {
    for (const membre of grappe.membres) {
      clusterOf.set(membre, grappe.numero);
    }
  }

  const listeAretes = [...aretes.values()].sort(
    (gauche, droite) =>
      ordre(gauche.source, droite.source) ||
      ordre(gauche.target, droite.target) ||
      gauche.type.localeCompare(droite.type, 'fr'),
  );
  for (const arete of listeAretes) {
    const grappe = clusterOf.get(arete.source);
    // A doubled pair is already flagged as such: counting its incompatibility
    // half as internal too would report the one mistake twice.
    arete.interne =
      arete.type === 'INCOMPATIBILITE' &&
      !arete.doublee &&
      grappe !== undefined &&
      grappe === clusterOf.get(arete.target);
  }

  const noeuds = [...compteurs.entries()]
    .map(([id, compteur]) => ({
      id,
      label: labels.get(id) ?? id,
      inconnu: !referentiel.has(id),
      affinites: compteur.affinites,
      incompatibilites: compteur.incompatibilites,
      grappe: clusterOf.get(id) ?? null,
    }))
    .sort((gauche, droite) => ordre(gauche.id, droite.id));

  return {
    noeuds,
    aretes: listeAretes,
    grappes,
    isoles,
    incompatibilitesInternes: listeAretes.filter((arete) => arete.interne),
    sansPaire: animateurs.filter((animateur) => !compteurs.has(animateur.id)).length,
  };
}

/**
 * The affinity clusters, largest first and numbered from 1, and the people
 * tied by incompatibilities only; members in label order.
 */
function groupClusters(
  compteurs: Map<string, { affinites: number; incompatibilites: number }>,
  partition: Partition,
  ordre: (gauche: string, droite: string) => number,
): { grappes: GrappeReseau[]; isoles: string[] } {
  const classes = new Map<string, string[]>();
  const isoles: string[] = [];
  for (const [id, compteur] of compteurs) {
    if (compteur.affinites === 0) {
      isoles.push(id);
      continue;
    }
    const racine = partition.find(id);
    classes.set(racine, [...(classes.get(racine) ?? []), id]);
  }
  for (const membres of classes.values()) {
    membres.sort(ordre);
  }
  isoles.sort(ordre);
  const grappes = [...classes.values()]
    .sort((gauche, droite) => droite.length - gauche.length || ordre(gauche[0], droite[0]))
    .map((membres, index) => ({ numero: index + 1, membres }));
  return { grappes, isoles };
}

/** One edge per (type, pair), gathering every adjustment that draws it. */
function collectEdges(contraintes: readonly ContrainteAdHoc[]): Map<string, AreteReseau> {
  const aretes = new Map<string, AreteReseau>();
  for (const contrainte of contraintes) {
    if (!isPairType(contrainte.type)) {
      continue;
    }
    const [premier, second] = (contrainte.animateursConcernes ?? []).map((ref) => ref?.id);
    if (!premier || !second || premier === second) {
      continue;
    }
    const [source, target] = premier < second ? [premier, second] : [second, premier];
    const key = `${contrainte.type}:${source}|${target}`;
    let arete = aretes.get(key);
    if (!arete) {
      arete = {
        key,
        type: contrainte.type,
        source,
        target,
        contraintes: [],
        restreinte: false,
        interne: false,
        doublee: false,
      };
      aretes.set(key, arete);
    }
    arete.contraintes.push(contrainte);
    // `!= null`: an older payload may omit the field rather than send null.
    arete.restreinte ||= contrainte.creneau != null || contrainte.stand != null;
  }

  return aretes;
}

/** Each endpoint's label and counts, and the affinity classes the edges draw. */
function readEndpoints(
  aretes: Map<string, AreteReseau>,
  referentiel: Map<string, Animateur>,
): {
  labels: Map<string, string>;
  compteurs: Map<string, { affinites: number; incompatibilites: number }>;
  partition: Partition;
} {
  const labels = new Map<string, string>();
  const compteurs = new Map<string, { affinites: number; incompatibilites: number }>();
  const partition = new Partition();
  for (const arete of aretes.values()) {
    for (const id of [arete.source, arete.target]) {
      const animateur = referentiel.get(id);
      labels.set(
        id,
        animateur
          ? nomAffiche(animateur)
          : $localize`:@@adHoc.reseau.inconnu:animateur inconnu (${id}:id:)`,
      );
      const compteur = compteurs.get(id) ?? { affinites: 0, incompatibilites: 0 };
      if (arete.type === 'AFFINITE') {
        compteur.affinites += 1;
      } else {
        compteur.incompatibilites += 1;
      }
      compteurs.set(id, compteur);
    }
    if (arete.type === 'AFFINITE') {
      partition.union(arete.source, arete.target);
    }
    const autre = `${arete.type === 'AFFINITE' ? 'INCOMPATIBILITE' : 'AFFINITE'}:${arete.source}|${arete.target}`;
    arete.doublee = aretes.has(autre);
  }
  return { labels, compteurs, partition };
}

/* ---------------------------------- Layout ---------------------------------- */

/** Beyond this many members, a circle is unreadable: the cluster is laid out in columns. */
export const SEUIL_COLONNES = 30;

const ESPACE_NOEUD = 46;
const MARGE_LIBELLE = 110;
const MARGE_TITRE = 28;
const FRAME_GAP = 24;
const ROWS_PER_COLUMN = 15;
const LARGEUR_COLONNE = 170;
const HAUTEUR_LIGNE = 30;
/** Left of a column: room for the edges that bow out of it (see {@link COLUMN_ARC_OFFSET}). */
const MARGE_COLONNE = 34;
/**
 * How far an edge between two members of one column, or of one row, bows
 * away from the line holding them: past the largest node, so it passes beside
 * the members in between instead of through them.
 */
const COLUMN_ARC_OFFSET = 22;

export interface PositionNoeud {
  id: string;
  x: number;
  y: number;
  rayon: number;
  /** Where the label sits and which way it reads, away from the centre of its circle. */
  libelleX: number;
  libelleY: number;
  ancre: 'start' | 'middle' | 'end';
}

/** A drawn frame: an affinity cluster, or the people tied by incompatibilities only. */
export interface CadreReseau {
  /** The cluster number; null for the frame of the people tied by incompatibilities only. */
  grappe: number | null;
  x: number;
  y: number;
  largeur: number;
  hauteur: number;
  taille: number;
  enColonnes: boolean;
}

export interface TraceArete {
  key: string;
  /** SVG path: a straight line inside a frame, a curve between frames or for a doubled pair. */
  d: string;
  /** The middle of the path, where a narrowed edge carries its diamond. */
  milieuX: number;
  milieuY: number;
}

export interface Disposition {
  largeur: number;
  hauteur: number;
  cadres: CadreReseau[];
  noeuds: Map<string, PositionNoeud>;
  aretes: TraceArete[];
}

/** A node grows with its number of pairs, up to a cap. */
export function rayonNoeud(noeud: NoeudReseau): number {
  return 6 + Math.min(noeud.affinites + noeud.incompatibilites, 6) * 1.5;
}

const arrondi = (valeur: number): number => Math.round(valeur * 10) / 10;

/** Vertical nudge of a label, so it clears its node below, above or level with it. */
function decalageLibelleY(sin: number): number {
  if (sin > 0.3) {
    return 10;
  }
  return sin < -0.3 ? -4 : 4;
}

interface Bloc {
  grappe: number | null;
  membres: string[];
  largeur: number;
  hauteur: number;
  enColonnes: boolean;
}

function bloc(grappe: number | null, membres: string[]): Bloc {
  if (membres.length > SEUIL_COLONNES) {
    const colonnes = Math.ceil(membres.length / ROWS_PER_COLUMN);
    return {
      grappe,
      membres,
      enColonnes: true,
      largeur: colonnes * LARGEUR_COLONNE + MARGE_COLONNE,
      hauteur: MARGE_TITRE + Math.min(membres.length, ROWS_PER_COLUMN) * HAUTEUR_LIGNE + 10,
    };
  }
  const rayon = Math.max(40, (membres.length * ESPACE_NOEUD) / (2 * Math.PI));
  return {
    grappe,
    membres,
    enColonnes: false,
    largeur: 2 * rayon + 2 * MARGE_LIBELLE,
    hauteur: 2 * rayon + MARGE_TITRE + 50,
  };
}

/**
 * Where everything goes. Frames are packed on shelves, left to right within
 * `largeur`; inside a frame the members sit on a circle from twelve o'clock,
 * clockwise, or in columns past {@link SEUIL_COLONNES}. Nothing here reads a
 * clock or a random source: the same network gives the same layout.
 */
export function disposer(reseau: Reseau, largeur: number): Disposition {
  const byId = new Map(reseau.noeuds.map((noeud) => [noeud.id, noeud]));
  const blocs = reseau.grappes.map((grappe) => bloc(grappe.numero, grappe.membres));
  if (reseau.isoles.length > 0) {
    blocs.push(bloc(null, reseau.isoles));
  }

  const cadres: CadreReseau[] = [];
  const noeuds = new Map<string, PositionNoeud>();
  let x = 0;
  let y = 0;
  let hauteurEtagere = 0;
  let largeurTotale = 0;
  for (const courant of blocs) {
    if (x > 0 && x + courant.largeur > largeur) {
      x = 0;
      y += hauteurEtagere + FRAME_GAP;
      hauteurEtagere = 0;
    }
    cadres.push({
      grappe: courant.grappe,
      x,
      y,
      largeur: arrondi(courant.largeur),
      hauteur: arrondi(courant.hauteur),
      taille: courant.membres.length,
      enColonnes: courant.enColonnes,
    });
    placerMembres(courant, x, y, byId, noeuds);
    x += courant.largeur + FRAME_GAP;
    hauteurEtagere = Math.max(hauteurEtagere, courant.hauteur);
    largeurTotale = Math.max(largeurTotale, x - FRAME_GAP);
  }

  const blockOf = new Map<string, Bloc>();
  for (const courant of blocs) {
    for (const membre of courant.membres) {
      blockOf.set(membre, courant);
    }
  }
  const aretes = reseau.aretes.flatMap((arete) => {
    const source = noeuds.get(arete.source);
    const target = noeuds.get(arete.target);
    if (!source || !target) {
      return [];
    }
    const blocSource = blockOf.get(arete.source);
    const memeCadre = blocSource === blockOf.get(arete.target);
    if (memeCadre && blocSource?.enColonnes) {
      const arc = columnArc(arete, source, target);
      if (arc !== null) {
        return [trace(arete.key, source, target, arc)];
      }
    }
    // A doubled pair draws its two edges on either side of the straight line.
    let courbure = memeCadre ? 0 : 0.2;
    if (arete.doublee) {
      courbure = arete.type === 'AFFINITE' ? 0.15 : -0.15;
    }
    return [trace(arete.key, source, target, courbure)];
  });

  return {
    largeur: arrondi(Math.max(largeurTotale, 0)),
    hauteur: arrondi(blocs.length === 0 ? 0 : y + hauteurEtagere),
    cadres,
    noeuds,
    aretes,
  };
}

/**
 * The bend of an edge inside a frame laid out in columns, when a straight
 * line would run through other members: two members of one column more than
 * a row apart, or of one row more than a column apart. The edge then bows
 * out by a fixed {@link COLUMN_ARC_OFFSET} — to the left of a column, below a
 * row — whatever its length; a doubled pair bows its incompatibility further
 * out, so the two stay apart. Null when a straight line crosses nobody.
 */
function columnArc(
  arete: AreteReseau,
  source: PositionNoeud,
  target: PositionNoeud,
): number | null {
  const dx = target.x - source.x;
  const dy = target.y - source.y;
  const offset = COLUMN_ARC_OFFSET + (arete.doublee && arete.type === 'INCOMPATIBILITE' ? 14 : 0);
  // `trace` offsets its control point by (-dy, dx) × courbure, and the curve
  // reaches half that offset: the sign of the divisor fixes the side.
  if (dx === 0 && Math.abs(dy) > HAUTEUR_LIGNE) {
    return (2 * offset) / dy;
  }
  if (dy === 0 && Math.abs(dx) > LARGEUR_COLONNE) {
    return (2 * offset) / dx;
  }
  return null;
}

function placerMembres(
  courant: Bloc,
  x: number,
  y: number,
  byId: Map<string, NoeudReseau>,
  noeuds: Map<string, PositionNoeud>,
): void {
  const taille = courant.membres.length;
  if (courant.enColonnes) {
    courant.membres.forEach((id, index) => {
      const colonne = Math.floor(index / ROWS_PER_COLUMN);
      const ligne = index % ROWS_PER_COLUMN;
      const noeudX = x + MARGE_COLONNE + colonne * LARGEUR_COLONNE;
      const noeudY = y + MARGE_TITRE + 15 + ligne * HAUTEUR_LIGNE;
      const noeud = byId.get(id);
      noeuds.set(id, {
        id,
        x: arrondi(noeudX),
        y: arrondi(noeudY),
        rayon: noeud ? rayonNoeud(noeud) : 6,
        libelleX: arrondi(noeudX + 16),
        libelleY: arrondi(noeudY + 4),
        ancre: 'start',
      });
    });
    return;
  }
  const rayonCercle = (courant.largeur - 2 * MARGE_LIBELLE) / 2;
  const centreX = x + courant.largeur / 2;
  const centreY = y + MARGE_TITRE + 25 + rayonCercle;
  courant.membres.forEach((id, index) => {
    // From twelve o'clock, clockwise; a lone pair sits side by side.
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / taille + (taille === 2 ? Math.PI / 2 : 0);
    const cos = Math.cos(angle);
    const sin = Math.sin(angle);
    const noeud = byId.get(id);
    const rayon = noeud ? rayonNoeud(noeud) : 6;
    const noeudX = centreX + rayonCercle * cos;
    const noeudY = centreY + rayonCercle * sin;
    let ancre: PositionNoeud['ancre'] = 'middle';
    if (cos > 0.3) {
      ancre = 'start';
    } else if (cos < -0.3) {
      ancre = 'end';
    }
    noeuds.set(id, {
      id,
      x: arrondi(noeudX),
      y: arrondi(noeudY),
      rayon,
      libelleX: arrondi(noeudX + (rayon + 6) * cos),
      libelleY: arrondi(noeudY + (rayon + 6) * sin + decalageLibelleY(sin)),
      ancre,
    });
  });
}

/** A straight line when `courbure` is 0, else a quadratic curve bent sideways by that share of its length. */
function trace(
  key: string,
  source: PositionNoeud,
  target: PositionNoeud,
  courbure: number,
): TraceArete {
  const dx = target.x - source.x;
  const dy = target.y - source.y;
  if (courbure === 0) {
    return {
      key,
      d: `M ${source.x} ${source.y} L ${target.x} ${target.y}`,
      milieuX: arrondi(source.x + dx / 2),
      milieuY: arrondi(source.y + dy / 2),
    };
  }
  const controleX = arrondi(source.x + dx / 2 - dy * courbure);
  const controleY = arrondi(source.y + dy / 2 + dx * courbure);
  return {
    key,
    d: `M ${source.x} ${source.y} Q ${controleX} ${controleY} ${target.x} ${target.y}`,
    // A quadratic Bézier at t = ½: a quarter of each end, half of the control point.
    milieuX: arrondi(0.25 * source.x + 0.5 * controleX + 0.25 * target.x),
    milieuY: arrondi(0.25 * source.y + 0.5 * controleY + 0.25 * target.y),
  };
}

/** The summary the SVG carries as its accessible name: clusters, people, internal incompatibilities. */
export function resumeReseau(reseau: Reseau): string {
  return $localize`:@@adHoc.reseau.resume:${reseau.grappes.length}:grappes: grappe(s), ${reseau.noeuds.length}:personnes: personne(s), ${reseau.incompatibilitesInternes.length}:internes: incompatibilité(s) interne(s)`;
}
