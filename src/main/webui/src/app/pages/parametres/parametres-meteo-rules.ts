/**
 * The pure half of the « Alerte météo » block: a typed number clamped to what
 * the server accepts, and the words of a phenomenon.
 */

/**
 * The `min`/`max` of an input are a hint the browser gives, not a rule it
 * enforces on a typed value: clamping here keeps the request within what the
 * server accepts. A blank field is an edit in progress, not a value.
 */
export function borne(valeur: string, min: number, max: number): number | null {
  if (!valeur.trim()) {
    return null;
  }
  const saisi = Number(valeur);
  if (!Number.isFinite(saisi)) {
    return null;
  }
  return Math.min(Math.max(Math.round(saisi), min), max);
}

/** `chaleur`, `rafales`, `orage` as the screen says them; an unknown code is shown as is. */
export function phenomenonLabel(code: string): string {
  switch (code) {
    case 'chaleur':
      return $localize`:@@parametres.meteo.phenomene.chaleur:chaleur`;
    case 'rafales':
      return $localize`:@@parametres.meteo.phenomene.rafales:rafales`;
    case 'orage':
      return $localize`:@@parametres.meteo.phenomene.orage:orage`;
    default:
      return code;
  }
}
