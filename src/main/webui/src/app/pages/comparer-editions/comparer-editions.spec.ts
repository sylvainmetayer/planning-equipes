// The words of the comparison of two editions, without a component.

import { describe, expect, it } from 'vitest';
import { DeltaValueLine } from '../../core/models';
import {
  fieldsLabel,
  ficheUrl,
  flaggedByName,
  matchLabel,
  ratio,
  signed,
  valueLabel,
  valueText,
} from './comparer-editions';

function valeur(overrides: Partial<DeltaValueLine>): DeltaValueLine {
  return {
    group: 'LEGAL',
    key: 'dureeHebdomadaireMaxMinutes',
    label: null,
    referenceValue: '2880',
    targetValue: '2400',
    ...overrides,
  };
}

describe('comparer-editions', () => {
  it('signs a number with a true minus sign', () => {
    expect(signed(3)).toBe('+3');
    expect(signed(-12)).toBe('−12');
    expect(signed(0)).toBe('0');
  });

  it('reads a ratio as a percentage, and nobody available as a dash', () => {
    expect(ratio(0.5433)).toBe('54 %');
    expect(ratio(null)).toBe('—');
  });

  it('names the fields as the forms do, an unknown one as the server wrote it', () => {
    expect(fieldsLabel(['effectifMax', 'typologiesProposees', 'inconnu'])).toBe(
      'effectif maximum, typologies, inconnu',
    );
  });

  it('labels a legal parameter, and a constraint by its catalogue label', () => {
    expect(valueLabel(valeur({}))).toBe('Durée hebdomadaire maximale (minutes)');
    expect(valueLabel(valeur({ group: 'CONSTRAINT_ACTIVE', key: 'x', label: 'Repos' }))).toBe(
      'Repos',
    );
  });

  it('reads a switch in words and a weight as its effective value', () => {
    const interrupteur = valeur({ group: 'CONSTRAINT_ACTIVE' });
    expect(valueText(interrupteur, 'true')).toBe('activée');
    expect(valueText(interrupteur, 'false')).toBe('désactivée');
    expect(valueText(valeur({ group: 'CONSTRAINT_WEIGHT' }), '5')).toBe('5');
    expect(valueText(valeur({}), null)).toBe('—');
  });

  it('flags a name match for want of a code, never a day template matched by its name', () => {
    const ligne = {
      change: 'MODIFIED' as const,
      matching: 'NOM' as const,
      referenceId: '1',
      targetId: '7',
      code: null,
      label: 'Jour normal',
      fields: [],
    };
    expect(flaggedByName('STAND', ligne)).toBe(true);
    expect(flaggedByName('JOURNEE_TYPE', ligne)).toBe(false);
    expect(matchLabel('NOM', 'STAND')).toBe('par nom, faute de code');
    expect(matchLabel('NOM', 'JOURNEE_TYPE')).toBe('par nom');
  });

  it('leads a stand and an animateur to their fiche, the other families to their screen', () => {
    expect(ficheUrl('STAND', 'S1')).toBe('/stands/S1');
    expect(ficheUrl('ANIMATEUR', 'A151')).toBe('/animateurs/A151');
    expect(ficheUrl('EMPLACEMENT', 'L1')).toBe('/stands?onglet=lieux');
    expect(ficheUrl('STAND', null)).toBeNull();
  });
});
