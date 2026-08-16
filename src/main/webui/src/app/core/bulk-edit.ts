// Building blocks of the "modifier la sélection" dialogs.
//
// A bulk edit only ever touches the fields the user explicitly opted into: every
// field carries its own mode, and the neutral mode leaves each row's own value
// alone. That is what makes editing fifty rows at once safe — nothing is
// flattened to a common value by accident.

/** How a set-valued field (souhaits, typologies proposées) is edited. */
export type ModeListe = 'AUCUN' | 'AJOUTER' | 'RETIRER' | 'REMPLACER';

/** How a boolean field (manager, premium, …) is edited. */
export type ModeBooleen = 'INCHANGE' | 'OUI' | 'NON';

/**
 * Applies `mode` to one row's current values. Order is preserved and duplicates
 * are dropped, so applying the same edit twice is a no-op.
 */
export function appliquerModeListe(actuels: readonly string[], valeurs: readonly string[], mode: ModeListe): string[] {
  if (mode === 'AUCUN' || (valeurs.length === 0 && mode !== 'REMPLACER')) {
    return [...actuels];
  }
  if (mode === 'REMPLACER') {
    return [...new Set(valeurs)];
  }
  if (mode === 'RETIRER') {
    const aRetirer = new Set(valeurs);
    return actuels.filter((valeur) => !aRetirer.has(valeur));
  }
  return [...new Set([...actuels, ...valeurs])];
}

/** Applies `mode` to one row's current boolean; `INCHANGE` keeps it as it is. */
export function appliquerModeBooleen(actuel: boolean, mode: ModeBooleen): boolean {
  if (mode === 'INCHANGE') {
    return actuel;
  }
  return mode === 'OUI';
}
