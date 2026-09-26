// « Versions du plan » (issue #702): the finished solves and the snapshots of
// an edition, merged into one chronology. Pure, so the order, the wording of a
// row and the selection rules are tested without rendering the table.

import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry, PlanningKpi, PlanSnapshot } from '../../core/models';

/** What the comparator is told for the plan in place, in place of a snapshot id. */
export const CURRENT_PLAN = 'courant';

/**
 * One event of the plan's life. A finished solve carries its measures but no
 * stored plan of its own — the next solve's automatic snapshot is what keeps
 * it —, so only a snapshot restores, compares and ticks.
 */
export type VersionRow =
  | { kind: 'resolution'; key: string; date: string | null; entry: KpiHistoriqueEntry }
  | { kind: 'snapshot'; key: string; date: string | null; snapshot: PlanSnapshot };

/** Milliseconds of an instant, `-Infinity` for none: an undated row sinks to the bottom. */
function moment(date: string | null): number {
  const parsed = date ? Date.parse(date) : Number.NaN;
  return Number.isNaN(parsed) ? Number.NEGATIVE_INFINITY : parsed;
}

/**
 * The edition's solves and snapshots, newest first. At the same instant the
 * solve comes first: the automatic snapshot of the next one is taken later,
 * and a tie only means the two were written in the same millisecond.
 */
export function versionRows(
  entries: readonly KpiHistoriqueEntry[],
  snapshots: readonly PlanSnapshot[],
  editionId: string | null,
): VersionRow[] {
  const rows: VersionRow[] = [
    ...entries
      .filter((entry) => editionId === null || entry.editionId === editionId)
      .map((entry): VersionRow => ({
        kind: 'resolution',
        key: `r${entry.id}`,
        date: entry.creeLe,
        entry,
      })),
    ...snapshots
      .filter((snapshot) => editionId === null || snapshot.editionId === editionId)
      .map((snapshot): VersionRow => ({
        kind: 'snapshot',
        key: `s${snapshot.id}`,
        date: snapshot.creeLe,
        snapshot,
      })),
  ];
  return rows.sort(
    (a, b) =>
      moment(b.date) - moment(a.date) ||
      (a.kind === b.kind ? 0 : a.kind === 'resolution' ? -1 : 1) ||
      a.key.localeCompare(b.key),
  );
}

/**
 * A plan's measures in words — never « hard / medium / soft ». The server's
 * own verdict when the plan was read (`lecture`), and the seats staffed out of
 * those to fill, which every measure carries.
 */
export function kpiSentence(kpi: PlanningKpi | null): string {
  if (!kpi) {
    return '';
  }
  const verdict = (kpi.lecture ?? [])
    .filter((sentence) => sentence.sujet === 'VERDICT')
    .map((sentence) => sentence.texte);
  const parts = [...verdict];
  if (verdict.length === 0 && kpi.scoreHard !== null) {
    parts.push(
      kpi.scoreHard === 0
        ? $localize`:@@versions.phrase.durOk:Règles obligatoires tenues.`
        : $localize`:@@versions.phrase.durKo:Des règles obligatoires ne sont pas tenues.`,
    );
  }
  if (kpi.postesTotal > 0) {
    const pourvus = kpi.postesPourvus.toLocaleString(intlLocale());
    const total = kpi.postesTotal.toLocaleString(intlLocale());
    parts.push(
      $localize`:@@versions.phrase.couverture:${pourvus}:pourvus: places pourvues sur ${total}:total:.`,
    );
  }
  return parts.join(' ');
}

/** What a row says in its « Événement » column. */
export function eventLabel(row: VersionRow): string {
  if (row.kind === 'resolution') {
    const secondes = row.entry.kpi.dureeSolveSecondes;
    return secondes === null
      ? $localize`:@@versions.evenement.resolution:Résolution terminée`
      : $localize`:@@versions.evenement.resolutionDuree:Résolution terminée en ${formatSeconds(secondes)}:duree:`;
  }
  return row.snapshot.libelle;
}

function formatSeconds(secondes: number): string {
  if (secondes < 60) {
    return `${Math.round(secondes)} s`;
  }
  const minutes = Math.floor(secondes / 60);
  const reste = Math.round(secondes % 60);
  return reste === 0 ? `${minutes} min` : `${minutes} min ${reste} s`;
}

/** The measures of a row: a solve's own, or those its snapshot was captured with. */
export function rowKpi(row: VersionRow): PlanningKpi | null {
  return row.kind === 'resolution' ? row.entry.kpi : row.snapshot.kpi;
}

/**
 * The two sides of « Comparer », from the ticked selectors — snapshot ids as
 * text, or {@link CURRENT_PLAN}. The older side is the reference (A): a
 * comparison reads as « what changed since », which is what a list of versions
 * is looked at for. `null` unless exactly two are ticked.
 */
export function comparisonSides(
  ticked: readonly string[],
  snapshots: readonly PlanSnapshot[],
): { base: string; variante: string } | null {
  if (ticked.length !== 2) {
    return null;
  }
  const quand = (selector: string): number => {
    if (selector === CURRENT_PLAN) {
      return Number.POSITIVE_INFINITY;
    }
    const snapshot = snapshots.find((candidate) => String(candidate.id) === selector);
    return moment(snapshot?.creeLe ?? null);
  };
  const [first, second] = ticked;
  return quand(first) <= quand(second)
    ? { base: first, variante: second }
    : { base: second, variante: first };
}

/** `?comparer=a,b` → the two selectors it names, or nothing. */
export function readComparison(value: string | null): string[] {
  const selectors = (value ?? '')
    .split(',')
    .map((selector) => selector.trim())
    .filter((selector) => selector === CURRENT_PLAN || /^\d+$/.test(selector));
  return selectors.length === 2 && selectors[0] !== selectors[1] ? selectors : [];
}

/**
 * The edition's **last** publication — the plan the animateurs hold and the
 * one their espace reads (issue #245), and the only snapshot the server
 * refuses to delete (issue #34). The ordering mirrors the server's
 * (`publie_le` then `id`). Parsed, never compared as text: the fractional
 * seconds of an instant vary in length.
 */
export function lastPublishedId(snapshots: readonly PlanSnapshot[]): number | null {
  const published = snapshots
    .map((snapshot) => ({ snapshot, when: Date.parse(snapshot.publieLe ?? '') }))
    .filter((candidate) => !Number.isNaN(candidate.when));
  if (published.length === 0) {
    return null;
  }
  return published.reduce((last, candidate) =>
    candidate.when > last.when ||
    (candidate.when === last.when && candidate.snapshot.id > last.snapshot.id)
      ? candidate
      : last,
  ).snapshot.id;
}
