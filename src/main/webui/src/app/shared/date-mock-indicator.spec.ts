// The toolbar warning that this server's date is frozen (issue #297): it has to
// appear only when the mock is on, say what is frozen, and land the reader on
// the control rather than on the page.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ANCRE_DATE_DU_JOUR, DateMockService } from '../core/date-mock.service';
import { DateMockIndicator } from './date-mock-indicator';

describe('DateMockIndicator', () => {
  let fixture: ComponentFixture<DateMockIndicator>;

  async function rendre(dateDuJour: string): Promise<void> {
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
            modifiable: signal(true),
            actif: () => date() !== ''
          }
        }
      ]
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

  /**
   * The two things anyone wants from this warning are to change the date or to
   * clear it, so the link carries the anchor of the field itself — not just the
   * page it lives on.
   */
  it('deep-links to the field, not merely to the debug page', async () => {
    await rendre('2026-07-08');

    const href = racine().querySelector('a')!.getAttribute('href');
    expect(href).toContain('/debug');
    expect(href).toContain(`focus=${ANCRE_DATE_DU_JOUR}`);
    expect(href).toContain(`#${ANCRE_DATE_DU_JOUR}`);
  });
});
