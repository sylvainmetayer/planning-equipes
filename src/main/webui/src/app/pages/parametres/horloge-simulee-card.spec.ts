// « Date et heure simulées » of Paramètres › Instance (issue #297, moved from
// the Débogage page by #718).
//
// Three things are worth a test here: the card only exists where the server
// says it may, it saves on change with no Validate button, and the deep link of
// the toolbar indicator really lands on the control instead of on the page.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DateMockService } from '../../core/date-mock.service';
import { HorlogeSimuleeCard } from './horloge-simulee-card';

describe('HorlogeSimuleeCard', () => {
  let fixture: ComponentFixture<HorlogeSimuleeCard>;
  let dates: {
    dateDuJour: ReturnType<typeof signal<string>>;
    heureMock: ReturnType<typeof signal<string>>;
    modifiable: ReturnType<typeof signal<boolean>>;
    actif: () => boolean;
    set: ReturnType<typeof vi.fn>;
  };

  async function rendre(
    options: {
      modifiable?: boolean;
      dateDuJour?: string;
      heureDuJour?: string;
      focus?: string | null;
    } = {},
  ): Promise<void> {
    const dateDuJour = signal(options.dateDuJour ?? '');
    const heureMock = signal(options.heureDuJour ?? '');
    const modifiable = signal(options.modifiable ?? true);
    dates = {
      dateDuJour,
      heureMock,
      modifiable,
      actif: () => dateDuJour() !== '',
      set: vi.fn(async (date: string, heure = '') => {
        dateDuJour.set(date);
        heureMock.set(date ? heure : '');
      }),
    };
    const params = convertToParamMap(options.focus ? { focus: options.focus } : {});
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: DateMockService, useValue: dates },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: params }, queryParamMap: of(params) },
        },
      ],
    });
    fixture = TestBed.createComponent(HorlogeSimuleeCard);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function champ(): HTMLInputElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('input#date-du-jour');
  }

  beforeEach(async () => {
    await rendre();
  });

  it('offers the field, empty, when the server allows it', () => {
    expect(champ()).not.toBeNull();
    expect(champ()!.value).toBe('');
  });

  /**
   * The interface hiding the card is a courtesy; the guard is the server, which
   * refuses the write whatever the client believes. This only checks the
   * courtesy — the refusal is covered by `DateJourJResourceTest`.
   */
  it('does not offer the card at all where the server refuses a simulated clock', async () => {
    await rendre({ modifiable: false });

    expect(champ()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('#horloge')).toBeNull();
  });

  it('carries the anchor the toolbar indicator links to', () => {
    expect((fixture.nativeElement as HTMLElement).querySelector('#horloge')).not.toBeNull();
  });

  it('saves on change, with no Validate button', async () => {
    champ()!.value = '2026-07-08';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(dates.set).toHaveBeenCalledWith('2026-07-08', '');
    const validate = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => /valider|enregistrer/i.test(each.textContent ?? ''));
    expect(validate).toBeUndefined();
  });

  it('hands the clock back to the machine in one click', async () => {
    await rendre({ dateDuJour: '2026-07-08', heureDuJour: '14:30' });
    const bouton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => (each.textContent ?? '').includes("Revenir à l'horloge de la machine"))!;
    expect(bouton.disabled).toBe(false);

    bouton.click();
    await fixture.whenStable();

    expect(dates.set).toHaveBeenLastCalledWith('', '');
  });

  it('clears the field back to the real clock', async () => {
    await rendre({ dateDuJour: '2026-07-08' });
    expect(champ()!.value).toBe('2026-07-08');

    champ()!.value = '';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(dates.set).toHaveBeenCalledWith('', '');
  });

  function champHeure(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector('input#heure-du-jour')!;
  }

  /** A time alone is refused server-side: the field waits for a date. */
  it('offers the time only once a date is frozen', async () => {
    expect(champHeure().disabled).toBe(true);

    await rendre({ dateDuJour: '2026-07-08' });

    expect(champHeure().disabled).toBe(false);
  });

  it('saves the time with the frozen date, on change', async () => {
    await rendre({ dateDuJour: '2026-07-08' });

    champHeure().value = '14:30';
    champHeure().dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(dates.set).toHaveBeenCalledWith('2026-07-08', '14:30');
  });

  it('keeps the frozen time when only the date changes, and drops it with the date', async () => {
    await rendre({ dateDuJour: '2026-07-08', heureDuJour: '14:30' });

    champ()!.value = '2026-07-09';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(dates.set).toHaveBeenLastCalledWith('2026-07-09', '14:30');

    champ()!.value = '';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(dates.set).toHaveBeenLastCalledWith('', '');
  });

  it('warns, next to the field, that the date is frozen', async () => {
    await rendre({ dateDuJour: '2026-07-08' });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Date simulée :');
  });

  /** A refusal belongs under the control it is about, not in a toast that scrolls away. */
  it('shows a refusal next to the field', async () => {
    dates.set = vi.fn(async () => {
      throw new Error('Figer la date du jour n’est possible qu’en mode développement');
    });

    champ()!.value = '2026-07-08';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('mode développement');
  });

  /** What the toolbar warning links to: the control, focused, not just the page. */
  it('focuses the field when reached through the deep link', async () => {
    await rendre({ focus: 'date-du-jour' });

    expect(champ()).not.toBeNull();
    expect(document.activeElement).toBe(champ());
  });

  it('leaves the focus alone on a plain visit', async () => {
    await rendre({ focus: null });

    expect(document.activeElement).not.toBe(champ());
  });
});
