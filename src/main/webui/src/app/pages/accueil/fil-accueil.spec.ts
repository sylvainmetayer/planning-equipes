// The home screen's feed as rendered: the alerts by name, the anomalies, the
// local history, one list, narrowed by the type of the address.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { NotificationService } from '../../core/notification.service';
import { FilAccueil, MESSAGES_OPEN_STORAGE_KEY } from './fil-accueil';

const rapport = {
  bloquants: 1,
  aVerifier: 0,
  informations: 0,
  familles: [],
  anomalies: [
    {
      famille: 'CRENEAUX' as const,
      gravite: 'BLOQUANT' as const,
      code: 'CRENEAU_HORS_OUVERTURE_STANDS',
      message: "Aucun des 2 stands n'est ouvert pendant le créneau.",
      objet: 'CRENEAU' as const,
      objetId: '7',
      date: null,
    },
  ],
};

describe('FilAccueil', () => {
  const analysesApi = { alerts: vi.fn() };
  const constraintsApi = { catalogue: vi.fn() };
  let fixture: ComponentFixture<FilAccueil>;

  beforeEach(() => {
    localStorage.clear();
    analysesApi.alerts.mockReset();
    constraintsApi.catalogue.mockResolvedValue({ contraintes: [] });
    analysesApi.alerts.mockResolvedValue([
      {
        type: 'RAPPEL_VEILLE_INJOIGNABLE',
        cle: 'A1|2026-07-11',
        declencheLe: '2026-07-10T18:00:00Z',
        libelle: 'Rappel de la veille impossible : aucune adresse e-mail sur la fiche.',
        severite: 'WARNING',
        animateurId: 'A1',
        nomAffiche: 'Camille Dupont',
      },
    ]);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: ConstraintsApi, useValue: constraintsApi },
      ],
    });
  });

  function create(): HTMLElement {
    fixture = TestBed.createComponent(FilAccueil);
    fixture.componentRef.setInput('coherence', rapport);
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('gathers the alerts by name, the anomalies and the local history in one list', async () => {
    localStorage.setItem(MESSAGES_OPEN_STORAGE_KEY, 'true');
    TestBed.inject(NotificationService).push('info', 'Import terminé');
    const element = create();
    await fixture.whenStable();

    await vi.waitFor(() => expect(text()).toContain('Camille Dupont'));
    expect(text()).toContain('aucune adresse e-mail');
    expect(text()).toContain("n'est ouvert pendant le créneau");
    expect(text()).toContain('Import terminé');
    expect(text()).toContain('Tout marquer comme lu');
    expect(element.querySelectorAll('li.notification-item')).toHaveLength(3);
    // Blocking first, then the warning, then the message.
    expect(
      Array.from(element.querySelectorAll('li.notification-item')).map((li) =>
        li.getAttribute('data-gravite'),
      ),
    ).toEqual(['blocage', 'avertissement', 'message']);
  });

  it('narrows to the type of the address, and counts every type in its filter', async () => {
    await TestBed.inject(Router).navigateByUrl('/?type=coherence');
    const element = create();
    await fixture.whenStable();
    await vi.waitFor(() => expect(text()).toContain('Alertes du plan (1)'));

    expect(element.querySelectorAll('li.notification-item')).toHaveLength(1);
    expect(text()).toContain("n'est ouvert pendant le créneau");
    expect(text()).not.toContain('Camille Dupont');
    expect(element.querySelector('a[aria-current="true"]')!.textContent).toContain(
      'Cohérence du référentiel (1)',
    );
    // A filter keeps the rest of the address and the bell's anchor.
    expect(element.querySelectorAll('nav a')[1].getAttribute('href')).toBe(
      '/?type=alertes#a-traiter',
    );
    expect(element.querySelectorAll('nav a')[0].getAttribute('href')).toBe('/#a-traiter');
    await TestBed.inject(Router).navigateByUrl('/');
  });

  it('marks a message as read from its own line', async () => {
    localStorage.setItem(MESSAGES_OPEN_STORAGE_KEY, 'true');
    const notifications = TestBed.inject(NotificationService);
    notifications.push('info', 'Import terminé');
    const element = create();
    await fixture.whenStable();
    fixture.detectChanges();

    const bouton = Array.from(element.querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Marquer comme lu'),
    )!;
    bouton.click();
    expect(notifications.unreadCount()).toBe(0);
  });

  it('folds the messages by default behind a button that counts them, and remembers the choice', async () => {
    TestBed.inject(NotificationService).push('info', 'Import terminé');
    const element = create();
    await fixture.whenStable();
    await vi.waitFor(() => expect(text()).toContain('Camille Dupont'));

    expect(text()).not.toContain('Import terminé');
    expect(text()).not.toContain("Effacer l'historique");
    const bouton = element.querySelector<HTMLButtonElement>('button[aria-controls="fil-liste"]')!;
    expect(bouton.textContent).toContain('Messages (1)');
    expect(bouton.getAttribute('aria-expanded')).toBe('false');

    bouton.click();
    fixture.detectChanges();
    expect(bouton.getAttribute('aria-expanded')).toBe('true');
    expect(text()).toContain('Import terminé');
    expect(localStorage.getItem(MESSAGES_OPEN_STORAGE_KEY)).toBe('true');
    expect(element.querySelector('#fil-liste')).not.toBeNull();

    bouton.click();
    fixture.detectChanges();
    expect(text()).not.toContain('Import terminé');
    expect(localStorage.getItem(MESSAGES_OPEN_STORAGE_KEY)).toBe('false');
  });

  it('shows the messages under their own filter whatever the fold', async () => {
    TestBed.inject(NotificationService).push('info', 'Import terminé');
    await TestBed.inject(Router).navigateByUrl('/?type=messages');
    const element = create();
    await fixture.whenStable();
    await vi.waitFor(() => expect(text()).toContain('Import terminé'));

    expect(element.querySelector('button[aria-controls="fil-liste"]')).toBeNull();
    expect(text()).toContain("Effacer l'historique");
    await TestBed.inject(Router).navigateByUrl('/');
  });

  it('lists the hard rules the last solve broke, with a link to the Problèmes tab and no person', async () => {
    constraintsApi.catalogue.mockResolvedValue({
      contraintes: [
        {
          name: 'mineurSeul',
          niveau: 'HARD',
          libelleCourt: 'Mineur seul sur un stand',
          matchCount: 2,
        },
        { name: 'ok', niveau: 'HARD', libelleCourt: 'Sans écart', matchCount: 0 },
        { name: 'souple', niveau: 'SOFT', libelleCourt: 'Préférence', matchCount: 9 },
      ],
    });
    fixture = TestBed.createComponent(FilAccueil);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-type="blocages"]');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('Mineur seul sur un stand');
    expect(rows[0].textContent).toContain('2 correspondance(s)');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/diagnostic?onglet=problemes');
  });

  it('says when nothing is to be reported', async () => {
    analysesApi.alerts.mockResolvedValue([]);
    fixture = TestBed.createComponent(FilAccueil);
    await fixture.whenStable();
    expect(text()).toContain('Rien à signaler.');
  });
});

describe('FilAccueil — a weather alert', () => {
  it('leads to the consigne it suggests, without applying anything', async () => {
    const lien = '/consignes-solveur?onglet=consignes&date=2026-07-15&nouvelle=1&prereglage=P1';
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ConstraintsApi,
          useValue: { catalogue: () => Promise.resolve({ contraintes: [] }) },
        },
        {
          provide: AnalysesApi,
          useValue: {
            alerts: vi.fn().mockResolvedValue([
              {
                type: 'METEO_ALERTE',
                cle: '2026-07-15|HEAT|1',
                declencheLe: '2026-07-13T04:00:00Z',
                libelle:
                  'Mer. 15/07 : 36 °C prévus à Kiosque (seuil 33 °C). Suggestion : Plan canicule.',
                severite: 'WARNING',
                animateurId: null,
                nomAffiche: null,
                lien,
              },
            ]),
          },
        },
      ],
    });
    const navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(FilAccueil);
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(element.querySelector('li a')).not.toBeNull();
    });
    const lienPreparer = element.querySelector<HTMLAnchorElement>('li a')!;
    expect(lienPreparer.textContent).toContain('Préparer la consigne');
    expect(lienPreparer.getAttribute('href')).toBe(lien);
    lienPreparer.click();
    expect(navigateByUrl).toHaveBeenCalledWith(lien);
  });
});
