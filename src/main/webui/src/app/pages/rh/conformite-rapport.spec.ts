import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RhApi } from '../../core/api/rh-api';
import { RapportConformite } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ConformiteRapport } from './conformite-rapport';

const RAPPORT: RapportConformite = {
  editionId: 'E1',
  editionNom: 'Edition test',
  publieLe: '2026-08-10T10:00:00Z',
  genereLe: '2026-08-11T10:00:00Z',
  plafonds: {
    hebdomadaireMajeurHeures: 48,
    hebdomadaireMineurHeures: 35,
    vacationHeures: 6,
    reposQuotidienHeures: 11,
    ecartsAuxDefauts: ['Durée hebdomadaire maximale : 44 h au lieu de 48 h par défaut'],
  },
  synthese: {
    personnes: 2,
    mineurs: 1,
    ecarts: 1,
    semainesEnDepassement: 1,
    heuresTravail: 70,
    heuresDimanche: 0,
    heuresFerie: 0,
    heuresNuit: 2,
  },
  semaines: ['2026-W34'],
  regles: [
    { nom: 'dureeHebdomadaireMax', libelle: 'Plafond hebdomadaire', mineurs: false, ecarts: 1 },
  ],
  personnes: [
    {
      animateurId: 'A1',
      nom: 'Ada Test',
      mineur: false,
      heuresParSemaine: { '2026-W34': 52 },
      heuresTotal: 52,
      plafondHebdomadaire: 48,
      semainesEnDepassement: ['2026-W34'],
      heuresDimanche: 0,
      heuresFerie: 0,
      heuresNuit: 0,
      reposQuotidienMinHeures: 13.5,
      ecartsDetail: [],
    },
    {
      animateurId: 'B1',
      nom: 'Bob Test',
      mineur: true,
      heuresParSemaine: { '2026-W34': 18 },
      heuresTotal: 18,
      plafondHebdomadaire: 35,
      semainesEnDepassement: [],
      heuresDimanche: 0,
      heuresFerie: 0,
      heuresNuit: 2,
      reposQuotidienMinHeures: null,
      ecartsDetail: [{ regle: 'nuit', libelle: 'Travail de nuit', texte: 'poste à 21:00' }],
    },
  ],
};

describe('ConformiteRapport', () => {
  function setup(rapport: () => Promise<RapportConformite>) {
    const api = fakeOf<RhApi>({
      rapport,
      pdf: () => Promise.resolve('ok'),
      csv: () => Promise.resolve('ok'),
    });
    const notifications = fakeOf<NotificationService>({ notify: vi.fn() });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(RhApi, api),
        provideFake(NotificationService, notifications),
      ],
    });
    return { api, notifications };
  }

  async function render() {
    const fixture = TestBed.createComponent(ConformiteRapport);
    fixture.componentRef.setInput('editionId', 'E1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  it('shows the ceilings, the breaches, the people and the minors on their own', async () => {
    const { api } = setup(() => Promise.resolve(RAPPORT));
    const fixture = await render();

    expect(api.rapport).toHaveBeenCalledWith('E1');
    expect(text(fixture)).toContain(
      'Durée hebdomadaire maximale : 44 h au lieu de 48 h par défaut',
    );
    expect(text(fixture)).toContain('Plafonds en vigueur au');
    expect(text(fixture)).toContain('Ada Test');
    expect(text(fixture)).toContain('2026-W34');
    expect(text(fixture)).toContain('Travail de nuit : poste à 21:00');
    expect(fixture.nativeElement.querySelectorAll('.conformite-mineurs li').length).toBe(1);
  });

  it('says so when nobody is a minor', async () => {
    setup(() => Promise.resolve({ ...RAPPORT, personnes: [RAPPORT.personnes[0]] }));
    expect(text(await render())).toContain("Aucun mineur n'est planifié");
  });

  it('exports in both formats and tells a failure', async () => {
    const { api, notifications } = setup(() => Promise.resolve(RAPPORT));
    const fixture = await render();
    const boutons = fixture.nativeElement.querySelectorAll('.conformite-actions button');
    boutons[0].click();
    boutons[1].click();
    await fixture.whenStable();
    expect(api.pdf).toHaveBeenCalledWith('E1');
    expect(api.csv).toHaveBeenCalledWith('E1');

    api.pdf.mockRejectedValueOnce(new Error('refus'));
    boutons[0].click();
    await fixture.whenStable();
    expect(notifications.notify).toHaveBeenCalled();
  });

  it('shows the refusal when the plan was never published', async () => {
    setup(() => Promise.reject(new Error('Aucun planning publié')));
    expect(text(await render())).toContain('Aucun planning publié');
  });
});
