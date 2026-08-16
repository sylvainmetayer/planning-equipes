import { describe, expect, it } from 'vitest';
import { Animateur } from '../../core/models';
import {
  AnimateurBulkPatch,
  appliquerPatchAnimateur,
  patchAnimateurEstVide,
  patchAnimateurVide
} from './animateur-bulk-edit';

function animateur(overrides: Partial<Animateur> = {}): Animateur {
  return {
    id: 'alice',
    prenom: 'Alice',
    nom: 'Martin',
    dateNaissance: '1990-01-01',
    manager: false,
    competences: { jeuxDeSociete: 'AUTONOME' },
    souhaits: ['jeuxDeSociete'],
    joursIndisponibles: ['2026-08-01'],
    ...overrides
  };
}

function patch(overrides: Partial<AnimateurBulkPatch> = {}): AnimateurBulkPatch {
  return { ...patchAnimateurVide(), ...overrides };
}

describe('patchAnimateurEstVide', () => {
  it('considère le formulaire neutre comme vide', () => {
    expect(patchAnimateurEstVide(patchAnimateurVide())).toBe(true);
  });

  // Un mode choisi sans valeur ne modifierait rien : le bouton doit rester
  // désactivé plutôt que déclencher N écritures sans effet.
  it('reste vide tant que le mode choisi n’a pas de valeur', () => {
    expect(patchAnimateurEstVide(patch({ competence: { mode: 'AJOUTER', typologie: '', niveau: 'REFERENT' } }))).toBe(
      true
    );
    expect(patchAnimateurEstVide(patch({ indisponibilite: { mode: 'AJOUTER', jour: '' } }))).toBe(true);
    expect(patchAnimateurEstVide(patch({ souhaits: { mode: 'AJOUTER', typologies: [] } }))).toBe(true);
  });

  it('n’est plus vide dès qu’un champ est réellement renseigné', () => {
    expect(patchAnimateurEstVide(patch({ manager: 'OUI' }))).toBe(false);
    expect(patchAnimateurEstVide(patch({ souhaits: { mode: 'REMPLACER', typologies: [] } }))).toBe(false);
  });
});

describe('appliquerPatchAnimateur', () => {
  it('ne touche à rien avec un patch neutre', () => {
    expect(appliquerPatchAnimateur(animateur(), patchAnimateurVide())).toEqual(animateur());
  });

  it('force le statut manager', () => {
    expect(appliquerPatchAnimateur(animateur(), patch({ manager: 'OUI' })).manager).toBe(true);
  });

  it('ajoute une appréciation et écrase le niveau déjà connu', () => {
    const resultat = appliquerPatchAnimateur(
      animateur(),
      patch({ competence: { mode: 'AJOUTER', typologie: 'jeuxDeSociete', niveau: 'REFERENT' } })
    );

    expect(resultat.competences).toEqual({ jeuxDeSociete: 'REFERENT' });
  });

  it('retire une appréciation', () => {
    const resultat = appliquerPatchAnimateur(
      animateur(),
      patch({ competence: { mode: 'RETIRER', typologie: 'jeuxDeSociete', niveau: 'AUTONOME' } })
    );

    expect(resultat.competences).toEqual({});
  });

  it('ajoute un souhait sans perdre les existants', () => {
    const resultat = appliquerPatchAnimateur(
      animateur(),
      patch({ souhaits: { mode: 'AJOUTER', typologies: ['jeuxDeRole'] } })
    );

    expect(resultat.souhaits).toEqual(['jeuxDeSociete', 'jeuxDeRole']);
  });

  it('ajoute puis retire un jour d’indisponibilité', () => {
    const ajoute = appliquerPatchAnimateur(
      animateur(),
      patch({ indisponibilite: { mode: 'AJOUTER', jour: '2026-08-02' } })
    );
    expect(ajoute.joursIndisponibles).toEqual(['2026-08-01', '2026-08-02']);

    const retire = appliquerPatchAnimateur(ajoute, patch({ indisponibilite: { mode: 'RETIRER', jour: '2026-08-01' } }));
    expect(retire.joursIndisponibles).toEqual(['2026-08-02']);
  });

  // Le statut mineur/majeur se déduit toujours de la date de naissance : une
  // modification en masse ne doit toucher à aucun champ d'identité.
  it('laisse identité et date de naissance intactes', () => {
    const resultat = appliquerPatchAnimateur(animateur(), patch({ manager: 'OUI' }));

    expect(resultat.id).toBe('alice');
    expect(resultat.nom).toBe('Martin');
    expect(resultat.dateNaissance).toBe('1990-01-01');
  });
});
