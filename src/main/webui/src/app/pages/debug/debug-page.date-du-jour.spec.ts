// The development-only "freeze today" field of the Débogage page (issue #297).
//
// Three things are worth a test here: the field only exists where the server
// says it may, it saves on change with no Validate button, and the deep link of
// the toolbar warning really lands on the control instead of on the page.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { DateMockService } from '../../core/date-mock.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { PlanningApi } from '../../core/api/planning-api';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { DebugPage } from './debug-page';

describe('DebugPage — date du jour', () => {
  let fixture: ComponentFixture<DebugPage>;
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
      /**
       * The tab the address names. The field lives on « Vérifications » since
       * issue #606, so that is the default here; the deep link of the toolbar
       * warning is the case where no tab is named and the page has to pick it.
       */
      onglet?: string | null;
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
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: DateMockService, useValue: dates },
        {
          provide: ApiService,
          useValue: { get: vi.fn(async () => ({ adminEmail: null })), post: vi.fn() },
        },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ConfirmationRecopie, useValue: { demander: vi.fn() } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn() } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: ReferenceDataStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: ProblemesStore, useValue: { reloadFeasibility: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: {
            solverBusy: () => false,
            editingLocked: () => false,
            activeJobDescription: () => '',
          },
        },
        // The page hosts the bundled-scenario picker, which lists on entry and
        // imports on click; neither is what this file is about.
        { provide: PlanningApi, useValue: { scenarioNames: vi.fn(async () => []) } },
        {
          provide: ScenarioImportService,
          useValue: {
            importer: vi.fn(async () => ({ status: 'cancelled', result: null })),
            recapitulatif: vi.fn(() => ''),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: (() => {
            const onglet = options.onglet === undefined ? 'verifications' : options.onglet;
            const params = convertToParamMap({
              ...(options.focus ? { focus: options.focus } : {}),
              ...(onglet ? { onglet } : {}),
            });
            return { snapshot: { queryParamMap: params }, queryParamMap: of(params) };
          })(),
        },
      ],
    });
    fixture = TestBed.createComponent(DebugPage);
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
   * The interface hiding the field is a courtesy; the guard is the server, which
   * refuses the write whatever the client believes. This only checks the
   * courtesy — the refusal is covered by `DateJourJResourceTest`.
   */
  it('does not offer the field at all outside development mode', async () => {
    await rendre({ modifiable: false });

    expect(champ()).toBeNull();
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

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Date figée');
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
    await rendre({ focus: 'date-du-jour', onglet: null });

    // The tab was not named, and the field it holds is nonetheless on screen.
    expect(champ()).not.toBeNull();
    expect(document.activeElement).toBe(champ());
  });

  it('leaves the focus alone on a plain visit', async () => {
    await rendre({ focus: null });

    expect(document.activeElement).not.toBe(champ());
  });
});
