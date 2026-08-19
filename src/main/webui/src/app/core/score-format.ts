// Human rendering of a HardMediumSoftScore delta, shared by the swap
// simulation dialog and the admin échanges screen. The backend serializes
// scores as objects ({hardScore, mediumScore, softScore}), never as strings —
// interpolating one directly renders "[object Object]".

import type { HardMediumSoftScore } from './models';

/** `+1hard / 0medium / -3soft` — an explicit `+` marks each improvement. */
export function formatDeltaScore(delta: HardMediumSoftScore): string {
  const sign = (value: number) => (value > 0 ? '+' : '');
  return `${sign(delta.hardScore)}${delta.hardScore}hard / ${sign(delta.mediumScore)}${delta.mediumScore}medium / ${sign(delta.softScore)}${delta.softScore}soft`;
}
