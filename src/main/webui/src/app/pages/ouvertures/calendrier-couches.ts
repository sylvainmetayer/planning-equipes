// The layers that decide the seats a solve will receive — the stand's own
// hours, the grid's timeslots, the consigne of the day — as the openings grid
// renders them (`?couches=`) and explains them in a sentence. Pure functions,
// tested without a DOM: the layers come from `GET
// /api/ouvertures-stands/couches`, which reads them off the two resolvers, so
// nothing here re-reads a rule. The grid draws them through `rendu-grille.ts`.

import { LayerCell, ConsigneLayer, LayerWindow } from '../../core/models';

/** The four layers a cell of the grid can draw, each behind its own checkbox. */
export type Couche = 'stand' | 'creneaux' | 'consigne' | 'resultat';

export const COUCHES: readonly Couche[] = ['stand', 'creneaux', 'consigne', 'resultat'];

/** What `?couches=` holds when every box is unticked: an empty value would read as « absent », i.e. all. */
const AUCUNE = 'aucune';

const MINUTES_PER_DAY = 24 * 60;

/** `?couches=stand,resultat` → the layers shown; absent = all four, unknown names ignored. */
export function readCouchesParam(param: string | null): Couche[] {
  if (param === null || param === '') {
    return [...COUCHES];
  }
  if (param === AUCUNE) {
    return [];
  }
  const demandees = new Set(param.split(','));
  return COUCHES.filter((couche) => demandees.has(couche));
}

/** The param for a set of layers: none when all four are shown, the default. */
export function writeCouchesParam(couches: readonly Couche[]): string | null {
  const affichees = COUCHES.filter((couche) => couches.includes(couche));
  if (affichees.length === COUCHES.length) {
    return null;
  }
  return affichees.length === 0 ? AUCUNE : affichees.join(',');
}

/** One layer ticked or unticked, the others kept, in their canonical order. */
export function toggleCouche(
  couches: readonly Couche[],
  couche: Couche,
  visible: boolean,
): Couche[] {
  return COUCHES.filter((each) => (each === couche ? visible : couches.includes(each)));
}

/** Minutes from the day's midnight → `HH:mm`; past midnight wraps. */
export function timeOf(minutes: number): string {
  const withinDay = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  const heures = Math.floor(withinDay / 60);
  const reste = withinDay % 60;
  return `${String(heures).padStart(2, '0')}:${String(reste).padStart(2, '0')}`;
}

/** `14:00–20:00`; a stretch running to midnight ends at `24:00`, not at the `00:00` it started from. */
function libelleIntervalle(debut: number, fin: number): string {
  return `${timeOf(debut)}–${fin === MINUTES_PER_DAY ? '24:00' : timeOf(fin)}`;
}

function listeFenetres(fenetres: readonly LayerWindow[]): string {
  return fenetres
    .map((fenetre) => libelleIntervalle(fenetre.debutMinutes, fenetre.finMinutes))
    .join(', ');
}

/**
 * The cell in one sentence: what is open, then which layer said so — « Ouvert
 * 14:00–18:00 : règle récurrente 14:00–20:00, amputée par la consigne « Plan
 * canicule » 18:00–20:00 ». Worded from what the server resolved; no rule is
 * re-read here.
 */
export function explainCell(cellule: LayerCell, consigne: ConsigneLayer | null): string {
  const etat =
    cellule.effective.length === 0
      ? $localize`:@@ouvertures.couches.explication.ferme:Fermé`
      : $localize`:@@ouvertures.couches.explication.ouvert:Ouvert ${listeFenetres(cellule.effective)}:fenetres:`;
  const nominal = explainNominal(cellule);
  if (!consigne) {
    return `${etat} : ${nominal}`;
  }
  const bande = libelleIntervalle(consigne.debutMinutes, consigne.finMinutes);
  const amputee = $localize`:@@ouvertures.couches.explication.consigne:amputé par la consigne « ${consigne.prereglage || consigne.motif}:consigne: » ${bande}:bande:`;
  const rouverte =
    cellule.reopenings.length === 0
      ? ''
      : ', ' +
        $localize`:@@ouvertures.couches.explication.reouverture:rouvert ${listeFenetres(cellule.reopenings)}:fenetres:`;
  return `${etat} : ${nominal}, ${amputee}${rouverte}`;
}

function explainNominal(cellule: LayerCell): string {
  const motif = cellule.motif ? ` (${cellule.motif})` : '';
  switch (cellule.source) {
    case 'DEFAUT':
      return $localize`:@@ouvertures.couches.explication.defaut:aucun horaire déclaré, ouvert par défaut`;
    case 'EXCEPTION':
      return cellule.nominal.length === 0
        ? $localize`:@@ouvertures.couches.explication.exceptionFermee:fermé par une exception datée${motif}:motif:`
        : $localize`:@@ouvertures.couches.explication.exception:exception datée ${listeFenetres(cellule.nominal)}:fenetres:${motif}:motif:`;
    case 'REGLE':
      return cellule.nominal.length === 0
        ? $localize`:@@ouvertures.couches.explication.regleFermee:les règles du stand ne l'ouvrent pas ce jour-là`
        : $localize`:@@ouvertures.couches.explication.regle:règle récurrente ${listeFenetres(cellule.nominal)}:fenetres:${motif}:motif:`;
  }
}
