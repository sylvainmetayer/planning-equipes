// The bundled-scenario picker, on the Débogage screen. Three things matter and
// nothing else does: the dropdown always has a target so the button cannot fire
// on nothing, the import goes through the shared choreography (which is what
// confirms and snapshots), and a solve running on this edition closes the door.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ScenarioPreenregistre } from './scenario-preenregistre';

describe('ScenarioPreenregistre', () => {
  const planningApi = { scenarioNames: vi.fn() };
  const scenarioImport = { importer: vi.fn(), recapitulatif: vi.fn() };
  const editingLocked = signal(false);

  let fixture: ComponentFixture<ScenarioPreenregistre>;

  type Internals = {
    onSelectScenario: (name: string) => void;
    onLoadSample: () => Promise<void>;
  };

  beforeEach(() => {
    editingLocked.set(false);
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
        { provide: PlanningApi, useValue: planningApi },
        { provide: ScenarioImportService, useValue: scenarioImport },
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
});
