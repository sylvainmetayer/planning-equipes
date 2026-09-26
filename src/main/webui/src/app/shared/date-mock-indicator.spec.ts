// The toolbar warning that this server's date is frozen (issue #297): it has to
// appear only when the mock is on, say what is frozen, and land the reader on
// the control rather than on the page.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { CLOCK_CARD_ANCHOR, TODAY_ANCHOR, DateMockService } from '../core/date-mock.service';
import { DateMockIndicator } from './date-mock-indicator';

describe('DateMockIndicator', () => {
  let fixture: ComponentFixture<DateMockIndicator>;

  async function rendre(dateDuJour: string, heureDuJour = ''): Promise<void> {
    const date = signal(dateDuJour);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: DateMockService,
          useValue: {
            dateDuJour: date,
            libelle: () => [date(), heureDuJour].filter(Boolean).join(' '),
            modifiable: signal(true),
            actif: () => date() !== '',
          },
        },
      ],
    });
    fixture = TestBed.createComponent(DateMockIndicator);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('stays out of the way while the real clock is in use', async () => {
    await rendre('');

    expect(racine().querySelector('a')).toBeNull();
  });

  it('shows up, and names the frozen date, once the mock is on', async () => {
    await rendre('2026-07-08');

    const lien = racine().querySelector('a');
    expect(lien).not.toBeNull();
    expect(lien!.getAttribute('aria-label')).toContain('MOCK');
    expect(lien!.getAttribute('aria-label')).toContain('2026-07-08');
  });

  it('names the frozen time too when there is one', async () => {
    await rendre('2026-07-08', '14:30');

    expect(racine().querySelector('a')!.getAttribute('aria-label')).toContain('2026-07-08 14:30');
  });

  /**
   * The two things anyone wants from this warning are to change the date or to
   * clear it, so the link carries the anchor of the field itself — not just the
   * page it lives on.
   */
  it('deep-links to the field of Paramètres › Instance, not merely to the page', async () => {
    await rendre('2026-07-08');

    const href = racine().querySelector('a')!.getAttribute('href');
    expect(href).toContain('/parametres');
    expect(href).toContain('onglet=instance');
    expect(href).toContain(`focus=${TODAY_ANCHOR}`);
    expect(href).toContain(`#${CLOCK_CARD_ANCHOR}`);
  });
});
