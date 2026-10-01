// The recent messages under « À traiter aujourd'hui »: what the Notifications
// page held — the night's alerts by name, the local history — read only once
// unfolded, and unfolded by the lines that point at them.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { NotificationService } from '../../core/notification.service';
import { DateMockService } from '../../core/date-mock.service';
import { MessagesRecents, dayLabel, serverToday } from './messages-recents';

describe('MessagesRecents', () => {
  const analysesApi = { alerts: vi.fn() };
  let fixture: ComponentFixture<MessagesRecents>;

  beforeEach(() => {
    localStorage.clear();
    analysesApi.alerts.mockReset();
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
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: DateMockService, useValue: { dateDuJour: () => '' } },
      ],
    });
    fixture = TestBed.createComponent(MessagesRecents);
  });

  function text(): string {
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('stays folded, and reads nothing from the server, until asked', async () => {
    await fixture.whenStable();

    expect(text()).toContain('Messages récents');
    expect(text()).not.toContain('Alertes des envois de nuit');
    expect(analysesApi.alerts).not.toHaveBeenCalled();
  });

  it('names who the night could not reach once unfolded, and keeps the local history', async () => {
    TestBed.inject(NotificationService).push('info', 'Import terminé');
    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    await fixture.whenStable();

    await vi.waitFor(() => expect(text()).toContain('Camille Dupont'));
    expect(text()).toContain('aucune adresse e-mail');
    expect(text()).toContain('Import terminé');
    expect(text()).toContain('Tout marquer comme lu');
  });

  it('unfolds when a line of « À traiter » points at the alerts of the night', async () => {
    fixture.componentRef.setInput('ouvert', true);
    await fixture.whenStable();

    await vi.waitFor(() => expect(analysesApi.alerts).toHaveBeenCalledOnce());
    expect((fixture.nativeElement as HTMLElement).querySelector('#alertes-nuit')).not.toBeNull();
  });

  /** Folded by the reader, then pointed at again: the page's asking wins once more. */
  it('unfolds again each time a line points at the alerts, after the reader folded them', async () => {
    const element = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('ouvert', true);
    await fixture.whenStable();
    expect(text()).toContain('Alertes des envois de nuit');

    element.querySelector('button')!.click();
    await fixture.whenStable();
    expect(text()).not.toContain('Alertes des envois de nuit');
    // Nothing to point at while folded.
    expect(element.querySelector('button')!.hasAttribute('aria-controls')).toBe(false);

    fixture.componentRef.setInput('ouvert', false);
    await fixture.whenStable();
    fixture.componentRef.setInput('ouvert', true);
    await fixture.whenStable();

    expect(text()).toContain('Alertes des envois de nuit');
    expect(element.querySelector('button')!.getAttribute('aria-controls')).toBe(
      'messages-recents-contenu',
    );
  });

  /** The address leaving the alerts folds nothing the reader opened. */
  it('stays open when the page stops asking', async () => {
    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    fixture.componentRef.setInput('ouvert', true);
    await fixture.whenStable();
    fixture.componentRef.setInput('ouvert', false);
    await fixture.whenStable();

    expect(text()).toContain('Alertes des envois de nuit');
  });

  it("says today and yesterday from the server's day, the frozen recette date included", () => {
    const frozenToday = serverToday('2026-07-10');
    expect(dayLabel(new Date(2026, 6, 10, 18, 5), frozenToday)).toBe("Aujourd'hui");
    expect(dayLabel(new Date(2026, 6, 9, 9, 0), frozenToday)).toBe('Hier');
    expect(dayLabel(new Date(2026, 6, 8, 9, 0), frozenToday)).not.toMatch(/Aujourd'hui|Hier/);
  });

  it('falls back to the clock when no date is frozen', () => {
    expect(dayLabel(new Date(), serverToday(''))).toBe("Aujourd'hui");
  });
});

describe('MessagesRecents — a weather alert', () => {
  it('leads to the consigne it suggests, without applying anything', async () => {
    const navigateByUrl = vi.fn().mockResolvedValue(true);
    const lien = '/consignes-solveur?onglet=consignes&date=2026-07-15&nouvelle=1&prereglage=P1';
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
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
        { provide: DateMockService, useValue: { dateDuJour: () => '' } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    });
    const fixture = TestBed.createComponent(MessagesRecents);
    fixture.componentRef.setInput('ouvert', true);
    await fixture.whenStable();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(element.querySelector('a')).not.toBeNull();
    });
    const lienPreparer = element.querySelector('a')!;
    expect(lienPreparer.textContent).toContain('Préparer la consigne');
    expect(lienPreparer.getAttribute('href')).toBe(lien);
    lienPreparer.click();
    expect(navigateByUrl).toHaveBeenCalledWith(lien);
  });
});
