import { describe, expect, it } from 'vitest';
import { RapportIntendance } from '../../core/models';
import { tableauxIntendance } from './intendance-page';

/**
 * The screen's only computation: turning the report into one table per window
 * of each day, with the column totals the intendance reads first — « entre 13
 * et 14 h, combien de plateaux ». Tested here rather than through the DOM, like
 * the other analysis screens.
 */
describe('tableauxIntendance', () => {
  const rapport: RapportIntendance = {
    pasMinutes: 60,
    message: '',
    journees: [
      {
        date: '2026-07-10',
        fenetres: [
          {
            libelle: 'midi',
            debut: '12:00:00',
            fin: '14:00:00',
            tranches: ['12:00:00', '13:00:00'],
            total: 5,
            totalMineurs: 1,
            emplacements: [
              {
                emplacementId: 1,
                emplacementNom: 'Hall Rouge',
                personnes: [0, 2],
                mineurs: [0, 1],
                total: 2,
                totalMineurs: 1,
              },
              {
                emplacementId: 2,
                emplacementNom: 'Pavillon Bleu',
                personnes: [3, 0],
                mineurs: [0, 0],
                total: 3,
                totalMineurs: 0,
              },
            ],
          },
        ],
      },
    ],
  };

  /**
   * A column names the band, not its start. « 12:00 » is a moment, and nobody
   * organises a service on a moment; « 12–13 » is what the intendance reads.
   * The end comes from the report's own step, so a band that stops being an
   * hour renames itself rather than lying.
   */
  it('builds one table per window, its columns named after the hourly bands', () => {
    const tableaux = tableauxIntendance(rapport);

    expect(tableaux).toHaveLength(1);
    expect(tableaux[0].titre).toBe('2026-07-10');
    expect(tableaux[0].fenetre).toBe('midi · 12:00–14:00');
    expect(tableaux[0].tranches).toEqual(['12:00–13:00', '13:00–14:00']);
  });

  it('names the band from the step the report itself gives', () => {
    const demiHeure = tableauxIntendance({
      ...rapport,
      pasMinutes: 30,
    });

    expect(demiHeure[0].tranches).toEqual(['12:00–12:30', '13:00–13:30']);
  });

  it('sums each hour across the emplacements', () => {
    // The column total is the number of trays to carry that hour; the row total
    // counts a person once, whatever the hours their break spans.
    expect(tableauxIntendance(rapport)[0].slotTotals).toEqual([3, 2]);
    expect(tableauxIntendance(rapport)[0].total).toBe(5);
  });

  it('names nobody, and says how many are minors', () => {
    const ligne = tableauxIntendance(rapport)[0].lignes[0];

    expect(ligne.emplacementNom).toBe('Hall Rouge');
    expect(ligne.cellules[1].personnes).toBe(2);
    expect(ligne.cellules[1].mineurs).toBe(1);
    expect(ligne.cellules[1].detail).toContain('dont 1 mineur');
    // An empty cell says nothing rather than « 0 personne en coupure ».
    expect(ligne.cellules[0].detail).toBe('');
  });

  it('has nothing to draw when the report is empty', () => {
    expect(tableauxIntendance(null)).toEqual([]);
    expect(tableauxIntendance({ pasMinutes: 60, journees: [], message: 'rien' })).toEqual([]);
  });
});
