// The bundled-scenario picker, on the Débogage screen. Three things matter and
// nothing else does: the dropdown always has a target so the button cannot fire
// on nothing, the import goes through the shared choreography (which is what
// confirms and snapshots), and a solve running on this edition closes the door.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../../core/api/editions-api';
import { PlanningApi } from '../../core/api/planning-api';
import { EtatGel } from '../../core/models';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ScenarioPreenregistre } from './scenario-preenregistre';

/** What `GET /api/editions/courant/gel` answers when two families are frozen. */
const FROZEN_STATES: EtatGel[] = [
  { famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'TYPOLOGIES_EMPLACEMENTS', libelle: 'Typologies', fige: false, figeLe: null },
  { famille: 'COMPETENCES', libelle: 'Compétences', fige: false, figeLe: null },
];

describe('ScenarioPreenregistre', () => {
  const planningApi = { scenarioNames: vi.fn() };
  const scenarioImport = { importer: vi.fn(), recapitulatif: vi.fn() };
  const editingLocked = signal(false);
  const gel = vi.fn<() => Promise<EtatGel[]>>();

  let fixture: ComponentFixture<ScenarioPreenregistre>;

  type Internals = {
    onSelectScenario: (name: string) => void;
    onLoadSample: () => Promise<void>;
  };

  beforeEach(() => {
    editingLocked.set(false);
    gel.mockReset();
    gel.mockResolvedValue([]);
    planningApi.scenarioNames.mockReset();
    scenarioImport.importer.mockReset();
    scenarioImport.recapitulatif.mockReset();
    planningApi.scenarioNames.mockResolvedValue([
      'extreme-10-sans-animateur.yaml',
      'gamme-01-1j-2stands-3animateurs.yaml',
      'scenario-complet.yaml',
    ]);
    scenarioImport.importer.mockResolvedValue({ status: 'imported', result: null });
    scenarioImport.recapitulatif.mockImplementation(
      (_result: unknown, fallback: string) => fallback,
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningApi, useValue: planningApi },
        { provide: ScenarioImportService, useValue: scenarioImport },
        { provide: EditionsApi, useValue: { gel } },
        {
          provide: SolverJobService,
          useValue: {
            editingLocked,
            activeJobDescription: () => 'Une résolution est en cours.',
          },
        },
      ],
    });
  });

  async function monter(): Promise<{ page: Internals; racine: HTMLElement }> {
    fixture = TestBed.createComponent(ScenarioPreenregistre);
    await fixture.whenStable();
    return {
      page: fixture.componentInstance as unknown as Internals,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  function bouton(): HTMLButtonElement {
    const trouve = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => each.textContent!.includes('Charger le scénario sélectionné'));
    expect(trouve, 'bouton « Charger » absent').toBeDefined();
    return trouve as HTMLButtonElement;
  }

  it('lists the bundled scenarios and preselects one, so the button always has a target', async () => {
    const { racine } = await monter();

    expect(
      racine.querySelector('.scenario-select .mat-mdc-select-value')!.textContent!.trim(),
    ).toBe('extreme-10-sans-animateur.yaml');
    expect(bouton().disabled).toBe(false);
  });

  it('imports the selected scenario by name, through the shared choreography', async () => {
    const { page } = await monter();

    page.onSelectScenario('gamme-01-1j-2stands-3animateurs.yaml');
    await page.onLoadSample();

    expect(scenarioImport.importer).toHaveBeenCalledExactlyOnceWith({
      kind: 'name',
      name: 'gamme-01-1j-2stands-3animateurs.yaml',
    });
  });

  /** A declined confirmation is not a failure: the panel goes back to saying nothing. */
  it('says nothing when the operator declines the confirmation', async () => {
    scenarioImport.importer.mockResolvedValue({ status: 'cancelled', result: null });
    const { page, racine } = await monter();

    await page.onLoadSample();
    await fixture.whenStable();

    expect(racine.textContent).not.toContain('Planning d');
  });

  it('refuses to write while a solve holds this edition', async () => {
    const { page } = await monter();
    editingLocked.set(true);
    await fixture.whenStable();

    expect(bouton().disabled).toBe(true);
    // And again in the handler, for the job that starts between render and click.
    await page.onLoadSample();
    expect(scenarioImport.importer).not.toHaveBeenCalled();
  });

  it('reports a failed listing instead of an empty dropdown with no explanation', async () => {
    planningApi.scenarioNames.mockRejectedValue(new Error('réseau coupé'));
    const { racine } = await monter();

    expect(racine.textContent).toContain('réseau coupé');
  });

  // A bundled scenario may name another edition: the freeze of this one warns,
  // the server judges, and the button stays available.
  it('warns that this edition is frozen, and still loads a scenario', async () => {
    gel.mockResolvedValue(FROZEN_STATES);
    const { page, racine } = await monter();

    const notice = racine.querySelector('app-gel-edition-notice .gel-edition-notice');
    expect(notice).not.toBeNull();
    expect(notice!.textContent).toContain('« Stands » et « Créneaux »');
    expect(notice!.textContent).toContain('sera refusé jusqu');
    expect(bouton().disabled).toBe(false);

    await page.onLoadSample();
    expect(scenarioImport.importer).toHaveBeenCalledOnce();
  });

  it('says nothing about the freeze while every family is open', async () => {
    const { racine } = await monter();

    expect(racine.querySelector('app-gel-edition-notice .gel-edition-notice')).toBeNull();
    expect(bouton().disabled).toBe(false);
  });
});
