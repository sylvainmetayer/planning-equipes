import { describe, expect, it } from 'vitest';
import { AnomalieOuverture } from '../../core/models';
import { anomalyLabel } from './stand-detail';

describe('anomalyLabel', () => {
  const anomaly = (partial: Partial<AnomalieOuverture>): AnomalieOuverture => ({
    type: 'FENETRE_SANS_EFFET',
    standId: 'S1',
    standNom: 'Stand',
    date: '2027-07-12',
    message: 'Fenêtre 07:00-08:00 hors de tout créneau',
    ...partial,
  });

  it('names a dated anomaly by its day, and an undated one by the whole edition', () => {
    expect(anomalyLabel(anomaly({}))).toBe('12/07');
    expect(anomalyLabel(anomaly({ type: 'STAND_JAMAIS_OUVERT', date: null }))).toBe(
      "Toute l'édition",
    );
  });

  // How the rules are written is named by its kind rather than a day.
  it('names the rule overlaps by their kind', () => {
    expect(anomalyLabel(anomaly({ type: 'REGLES_CHEVAUCHANTES', date: null }))).toBe(
      'Règles qui se recouvrent',
    );
    expect(anomalyLabel(anomaly({ type: 'REGLE_MASQUEE', date: null }))).toBe('Règle sans effet');
    expect(anomalyLabel(anomaly({ type: 'FENETRES_CHEVAUCHANTES' }))).toBe(
      'Fenêtres qui se recouvrent, 12/07',
    );
  });
});
