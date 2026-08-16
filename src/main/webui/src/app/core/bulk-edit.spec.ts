import { describe, expect, it } from 'vitest';
import { appliquerModeBooleen, appliquerModeListe } from './bulk-edit';

describe('appliquerModeListe', () => {
  it('laisse la ligne intacte en mode AUCUN', () => {
    expect(appliquerModeListe(['a'], ['b'], 'AUCUN')).toEqual(['a']);
  });

  it('ajoute sans dupliquer et conserve l’ordre', () => {
    expect(appliquerModeListe(['a', 'b'], ['b', 'c'], 'AJOUTER')).toEqual(['a', 'b', 'c']);
  });

  it('retire les valeurs demandées et ignore les absentes', () => {
    expect(appliquerModeListe(['a', 'b'], ['b', 'z'], 'RETIRER')).toEqual(['a']);
  });

  it('remplace tout, y compris par une liste vide', () => {
    expect(appliquerModeListe(['a', 'b'], ['c'], 'REMPLACER')).toEqual(['c']);
    expect(appliquerModeListe(['a'], [], 'REMPLACER')).toEqual([]);
  });

  // Une sélection de valeurs vide en ajout/retrait ne doit rien changer, sans
  // quoi un formulaire à moitié rempli viderait les lignes.
  it('ne change rien quand aucune valeur n’est choisie', () => {
    expect(appliquerModeListe(['a'], [], 'AJOUTER')).toEqual(['a']);
    expect(appliquerModeListe(['a'], [], 'RETIRER')).toEqual(['a']);
  });
});

describe('appliquerModeBooleen', () => {
  it('conserve la valeur de chaque ligne en mode INCHANGE', () => {
    expect(appliquerModeBooleen(true, 'INCHANGE')).toBe(true);
    expect(appliquerModeBooleen(false, 'INCHANGE')).toBe(false);
  });

  it('force la valeur choisie', () => {
    expect(appliquerModeBooleen(false, 'OUI')).toBe(true);
    expect(appliquerModeBooleen(true, 'NON')).toBe(false);
  });
});
