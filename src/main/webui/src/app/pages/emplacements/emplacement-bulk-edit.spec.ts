import { describe, expect, it } from 'vitest';
import { Emplacement } from '../../core/models';
import {
  appliquerPatchEmplacement,
  patchEmplacementEstVide,
  patchEmplacementVide
} from './emplacement-bulk-edit';

const kiosque: Emplacement = { id: 'kiosque', nom: 'Kiosque', latitude: 47.2, longitude: -1.55 };

describe('patchEmplacementEstVide', () => {
  it('considère le formulaire neutre comme vide', () => {
    expect(patchEmplacementEstVide(patchEmplacementVide())).toBe(true);
  });

  // Une seule des deux coordonnées ne suffit pas à placer un point.
  it('reste vide tant que les deux coordonnées ne sont pas saisies', () => {
    expect(patchEmplacementEstVide({ coordonnees: { mode: 'DEFINIR', latitude: 47.2, longitude: null } })).toBe(true);
  });

  it('n’est plus vide avec un point complet ou un effacement', () => {
    expect(patchEmplacementEstVide({ coordonnees: { mode: 'DEFINIR', latitude: 47.2, longitude: -1.55 } })).toBe(false);
    expect(patchEmplacementEstVide({ coordonnees: { mode: 'EFFACER', latitude: null, longitude: null } })).toBe(false);
  });
});

describe('appliquerPatchEmplacement', () => {
  it('ne touche à rien avec un patch neutre', () => {
    expect(appliquerPatchEmplacement(kiosque, patchEmplacementVide())).toEqual(kiosque);
  });

  it('place tous les emplacements sur le même point', () => {
    const resultat = appliquerPatchEmplacement(kiosque, {
      coordonnees: { mode: 'DEFINIR', latitude: 48.85, longitude: 2.35 }
    });

    expect(resultat).toEqual({ ...kiosque, latitude: 48.85, longitude: 2.35 });
  });

  it('efface les coordonnées', () => {
    const resultat = appliquerPatchEmplacement(kiosque, {
      coordonnees: { mode: 'EFFACER', latitude: null, longitude: null }
    });

    expect(resultat).toEqual({ ...kiosque, latitude: null, longitude: null });
  });

  it('conserve le nom de chaque emplacement', () => {
    const resultat = appliquerPatchEmplacement(kiosque, {
      coordonnees: { mode: 'DEFINIR', latitude: 48.85, longitude: 2.35 }
    });

    expect(resultat.nom).toBe('Kiosque');
  });
});
