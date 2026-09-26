import { describe, expect, it } from 'vitest';
import { DestinatairePublication } from '../../core/models';
import {
  changeCount,
  changeSummary,
  confirmationLabel,
  filterRecipients,
  readRecipientSort,
  sortRecipients,
} from './publication-diff';

function destinataire(partiel: Partial<DestinatairePublication> = {}): DestinatairePublication {
  return {
    animateurId: 'a1',
    nomAffiche: 'Alice Martin',
    email: 'alice@example.org',
    premiereDiffusion: false,
    changements: [],
    demandes: [],
    ajouts: 0,
    retraits: 0,
    deplacements: 0,
    mineur: false,
    reporte: false,
    confirmation: null,
    confirmeLe: null,
    jours: [],
    ...partiel,
  };
}

describe('readRecipientSort', () => {
  it('lit les deux ordres et ramène tout le reste au nom', () => {
    expect(readRecipientSort('ampleur')).toBe('ampleur');
    expect(readRecipientSort('nom')).toBe('nom');
    expect(readRecipientSort(null)).toBe('nom');
    expect(readRecipientSort('par-couleur')).toBe('nom');
  });
});

describe('sortRecipients', () => {
  /** The server already sends them by name: « par nom » must not reshuffle them. */
  it('laisse la liste telle que le serveur l’a envoyée', () => {
    const lignes = [destinataire({ nomAffiche: 'Zoé' }), destinataire({ nomAffiche: 'Alice' })];

    expect(sortRecipients(lignes, 'nom').map((each) => each.nomAffiche)).toEqual(['Zoé', 'Alice']);
  });

  it('met le plus gros changement en tête, à égalité par nom', () => {
    const lignes = [
      destinataire({ animateurId: 'a', nomAffiche: 'Zoé', ajouts: 1 }),
      destinataire({ animateurId: 'b', nomAffiche: 'Alice', ajouts: 1 }),
      destinataire({ animateurId: 'c', nomAffiche: 'Bruno', ajouts: 2, retraits: 1 }),
    ];

    expect(sortRecipients(lignes, 'ampleur').map((each) => each.nomAffiche)).toEqual([
      'Bruno',
      'Alice',
      'Zoé',
    ]);
  });

  it('ne modifie pas la liste reçue', () => {
    const lignes = [destinataire({ nomAffiche: 'Zoé' }), destinataire({ nomAffiche: 'Alice' })];
    sortRecipients(lignes, 'ampleur');

    expect(lignes.map((each) => each.nomAffiche)).toEqual(['Zoé', 'Alice']);
  });
});

describe('changeCount', () => {
  it('compte les trois natures de changement ensemble', () => {
    expect(changeCount(destinataire({ ajouts: 2, retraits: 1, deplacements: 3 }))).toBe(6);
  });
});

describe('filterRecipients', () => {
  it('replie les changements mineurs sans toucher aux autres', () => {
    const lignes = [destinataire({ mineur: true }), destinataire({ animateurId: 'a2' })];

    expect(filterRecipients(lignes, true)).toHaveLength(1);
    expect(filterRecipients(lignes, false)).toHaveLength(2);
  });
});

describe('changeSummary', () => {
  it('nomme la première diffusion plutôt que de compter des ajouts', () => {
    expect(changeSummary(destinataire({ premiereDiffusion: true, ajouts: 12 }))).toContain(
      'Première diffusion',
    );
  });

  it('énumère ce qui bouge, par nature', () => {
    const resume = changeSummary(destinataire({ ajouts: 2, deplacements: 1 }));

    expect(resume).toContain('2');
    expect(resume).toContain('1');
    expect(resume).not.toContain('retrait');
  });

  /** A decision to announce is a reason to write to somebody whose seats did not move. */
  it('dit la décision d’échange quand rien d’autre ne bouge', () => {
    expect(changeSummary(destinataire({ demandes: ['Votre demande a été acceptée.'] }))).toContain(
      'échange',
    );
  });
});

describe('confirmationLabel', () => {
  it('date la confirmation quand il y en a une', () => {
    const libelle = confirmationLabel(
      destinataire({ confirmation: 'CONFIRME', confirmeLe: '2026-07-10T08:00:00Z' }),
      'fr-FR',
    );

    expect(libelle).toContain('Confirmé');
  });

  it('distingue la relance sans réponse de l’absence de réponse', () => {
    expect(confirmationLabel(destinataire({ confirmation: 'RELANCE' }), 'fr-FR')).toContain(
      'Relancé',
    );
    expect(confirmationLabel(destinataire(), 'fr-FR')).toContain('Pas de réponse');
  });
});
