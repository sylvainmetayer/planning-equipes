// The « Prévenir les animateurs » box of the collection's opening, on an
// edition that is not the active one: the server refuses to mail from there
// (ADR 0072), so the box is greyed out with the sentence saying why, and the
// opening itself still goes through without the mail.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { EchangesApi } from '../../core/api/echanges-api';
import { EditionStore } from '../../core/edition.store';
import { JourJService } from '../../core/jour-j.service';
import { NotificationService } from '../../core/notification.service';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { seedStore } from '../../core/testing/seed-store';
import { GuichetsCard } from './guichets-card';

describe('GuichetsCard on an inactive edition', () => {
  const disponibilitesApi = fakeOf<DisponibilitesApi>({
    configuration: () =>
      Promise.resolve({
        collecteOuverte: false,
        debut: null,
        fin: null,
        relanceAutomatique: false,
      }),
    saveConfiguration: () =>
      Promise.resolve({ collecteOuverte: true, debut: null, fin: null, relanceAutomatique: false }),
  });
  let fixture: ComponentFixture<GuichetsCard>;

  beforeEach(async () => {
    disponibilitesApi.saveConfiguration.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideFake(DisponibilitesApi, disponibilitesApi),
        provideFake(
          EchangesApi,
          fakeOf<EchangesApi>({
            configuration: () =>
              Promise.resolve({
                foireOuverte: false,
                debut: null,
                fin: null,
                ouverteAujourdhui: false,
              }),
          }),
        ),
        provideFake(
          JourJService,
          fakeOf<JourJService>({
            configurationSignalements: () => Promise.resolve({ actifs: true }),
          }),
        ),
        provideFake(NotificationService, fakeOf<NotificationService>({ notify: () => undefined })),
      ],
    });
    seedStore(TestBed.inject(EditionStore), 'courant', {
      id: 'E1',
      nom: 'Année 2025',
      active: false,
      creeLe: null,
    });
    fixture = TestBed.createComponent(GuichetsCard);
    await fixture.whenStable();
    fixture.componentInstance['collectionOpen'].set(true);
    await fixture.whenStable();
  });

  it('greys out the mail box and says why next to it', () => {
    const root = fixture.nativeElement as HTMLElement;
    const box = Array.from(root.querySelectorAll('mat-checkbox')).find((each) =>
      each.textContent?.includes('Prévenir les animateurs'),
    )!;
    const input = box.querySelector('input')!;
    expect(input.disabled).toBe(true);
    expect(input.getAttribute('aria-describedby')).toContain('collecte-edition-inactive');
    expect(root.querySelector('#collecte-edition-inactive')!.textContent).toContain(
      "Cette édition n'est pas active",
    );
  });

  it('opens the collection without asking for the mail', async () => {
    fixture.componentInstance['prevenir'].set(true);
    await fixture.componentInstance['saveCollecte']();

    expect(disponibilitesApi.saveConfiguration.mock.calls[0][0].prevenirAnimateurs).toBe(false);
  });
});
