// « Règles du planning »: rendered for real on mocked APIs. What is pinned is
// what the screen decides beyond showing a catalogue — a rule is named by its
// short label, never its technical name; the block of a rule comes from the
// catalogue's fields, not from a name; the legal block is read only and links
// to Paramètres; a level is written only on « Enregistrer »; a mandatory rule
// shows no weight; a protected rule is switched off only once confirmed;
// `?regle=` opens the rule's panel, and the folded block when it is there.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSlideToggle, MatSlideToggleChange } from '@angular/material/slide-toggle';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { ConstraintView, ConstraintsView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { LegalDisableConfirmService } from './legal-disable-dialog';
import { ReglesPage } from './regles-page';

function rule(overrides: Partial<ConstraintView>): ConstraintView {
  return {
    name: 'r',
    niveau: 'HARD',
    categorie: 'Affectation',
    description: '',
    actif: true,
    protegee: false,
    legale: false,
    niveauReglable: false,
    dosable: false,
    poids: 1,
    score: '0hard/0medium/0soft',
    matchCount: 0,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    parametres: [],
    ...overrides,
  };
}

const HEBDO = rule({
  name: 'dureeHebdomadaireMax',
  libelleCourt: 'Durée hebdomadaire',
  categorie: 'Légal (temps de travail)',
  description: 'Au plus 48 h par semaine (art. L3121-20).',
  protegee: true,
  legale: true,
  parametres: [
    {
      libelle: 'Durée hebdomadaire maximale, majeurs',
      valeur: '48 h',
      lien: '/regles',
      onglet: 'regles',
      cle: 'dureeHebdomadaireMaxMinutes',
    },
  ],
});

const PLACES = rule({ name: 'posteDoitEtrePourvu', libelleCourt: 'Places pourvues' });

const CHARGE = rule({
  name: 'equilibrerCharge',
  libelleCourt: 'Charge équilibrée',
  niveau: 'MEDIUM',
  categorie: "Qualité d'organisation",
  dosable: true,
  niveauReglable: true,
  niveauChoisi: 'PREFERENCE_NORMALE',
  poids: 5,
  remediation: 'Cherchez qui est très au-dessus.',
  parametres: [
    {
      libelle: 'Jours travaillés d’affilée',
      valeur: '8',
      lien: '/regles',
      onglet: 'regles',
      cle: 'joursConsecutifsMax',
    },
  ],
});

/** A rule the law does not found and no edition levels: it lands in « Avancé ». */
const PROTEGEE = rule({
  name: 'encadrementMineur',
  libelleCourt: 'Sécurité des mineurs',
  categorie: 'Sécurité (mineurs)',
  protegee: true,
});

const PREFERENCE = rule({
  name: 'preserverBufferPolyvalents',
  libelleCourt: 'Réserve de polyvalents',
  niveau: 'SOFT',
  categorie: 'Préférences',
  poids: 5,
});

function catalogue(contraintes: ConstraintView[]): ConstraintsView {
  return {
    analysedAt: null,
    scoreGlobal: null,
    postesNonPourvus: null,
    faisabilite: null,
    hardScore: null,
    contraintesAdHocEnCause: [],
    scoreHorsPlancher: null,
    plancherMedium: null,
    plancherSoft: null,
    pivotEcarts: [],
    lecture: [],
    contraintes,
  };
}

describe('ReglesPage', () => {
  let fixture: ComponentFixture<ReglesPage>;
  let constraintsApi: Record<string, ReturnType<typeof vi.fn>>;
  let allowsDisabling: ReturnType<typeof vi.fn>;
  let ask: ReturnType<typeof vi.fn>;
  let askThreeWay: ReturnType<typeof vi.fn>;

  // The rules tab: the adjustments, the default tab, are another page's spec.
  async function rendre(
    params: Record<string, string> = { onglet: 'regles' },
    contraintes: ConstraintView[] = [PLACES, HEBDO, CHARGE, PROTEGEE, PREFERENCE],
  ): Promise<void> {
    constraintsApi = {
      catalogue: vi.fn(() => Promise.resolve(catalogue(contraintes))),
      setNiveau: vi.fn((_name: string, niveau: string) => Promise.resolve({ niveau })),
      setActive: vi.fn(() => Promise.resolve({ actif: false })),
      setWeight: vi.fn((_name: string, poids: number) => Promise.resolve({ poids })),
      history: vi.fn(() => Promise.resolve({ changes: [], resolutions: [] })),
      diagnose: vi.fn(),
    };
    allowsDisabling = vi.fn(() => Promise.resolve(false));
    ask = vi.fn(() => Promise.resolve(true));
    askThreeWay = vi.fn();
    const queryParams = new BehaviorSubject<ParamMap>(convertToParamMap(params));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(() => Promise.resolve([])) } },
        { provide: ConstraintsApi, useValue: constraintsApi },
        { provide: LegalDisableConfirmService, useValue: { allowsDisabling } },
        { provide: ConfirmService, useValue: { ask, askThreeWay } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: ProblemesStore,
          useValue: { shareConstraints: vi.fn(), alerteReglesLegales: () => '' },
        },
        {
          provide: SolverJobService,
          useValue: {
            onResult: () => () => undefined,
            editingLocked: () => false,
            solverBusy: () => false,
            activeJob: signal(null),
          },
        },
        {
          provide: PlanningResolutionStore,
          useValue: { reload: vi.fn(() => Promise.resolve(undefined)), resolution: () => null },
        },
        {
          provide: SolverSettingsService,
          useValue: {
            refresh: vi.fn(() => Promise.resolve(undefined)),
            bounds: signal(null),
            mailFinResolution: signal(false),
            secondsLimit: () => 900,
            effectivePlateauSeconds: () => 300,
          },
        },
        {
          provide: AdminApi,
          useValue: { mailConfig: vi.fn(() => Promise.resolve({ adminEmail: null })) },
        },
        { provide: EditionStore, useValue: { courant: () => ({ id: 'E1' }) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: queryParams.value, fragment: null },
            queryParamMap: queryParams,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ReglesPage);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(racine().querySelectorAll('tbody tr').length).toBeGreaterThan(0);
    });
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function page(): ReglesPage {
    return fixture.componentInstance;
  }

  beforeEach(async () => {
    await rendre();
  });

  /** « Réglages avancés » opened, as the button does. */
  async function openAdvanced(): Promise<void> {
    page()['avance'].basculer();
    await fixture.whenStable();
  }

  it('lists each rule in the block its catalogue fields name, by short label', async () => {
    const bloc = (id: string) =>
      Array.from(
        racine().querySelectorAll(`section[aria-labelledby="${id}"] tbody th[scope="row"]`),
      ).map((each) => each.textContent!.trim());
    expect(bloc('regles-bloc-legal')).toEqual(['Durée hebdomadaire']);
    expect(bloc('regles-bloc-organisation')).toEqual(['Charge équilibrée']);
    // Folded: the rest is not even created.
    expect(racine().querySelector('section[aria-labelledby="regles-bloc-avance"]')).toBeNull();

    await openAdvanced();

    expect(bloc('regles-bloc-avance')).toEqual([
      'Sécurité des mineurs',
      'Places pourvues',
      'Réserve de polyvalents',
    ]);
    expect(racine().textContent).not.toContain('dureeHebdomadaireMax');
    expect(racine().textContent).not.toContain('posteDoitEtrePourvu');
    expect(racine().querySelector('tbody tr .regles-article')?.textContent).toContain('L3121-20');
  });

  it('keeps the legal block read only, and links it to Paramètres › Légal', () => {
    const ligne = racine().querySelector(
      'section[aria-labelledby="regles-bloc-legal"] tbody tr',
    ) as HTMLElement;
    expect(ligne.querySelector('input, mat-slide-toggle, mat-button-toggle-group')).toBeNull();
    expect(ligne.querySelector('.regles-seuil-valeur')?.textContent).toContain('48 h');
    const lien = ligne.querySelector('a.regles-lien-parametres') as HTMLAnchorElement;
    expect(lien.getAttribute('href')).toBe('/parametres?onglet=legal');
  });

  it('shows an organisation threshold as stored, and links it to Paramètres › Organisation', () => {
    const ligne = racine().querySelector(
      'section[aria-labelledby="regles-bloc-organisation"] tbody tr',
    ) as HTMLElement;
    expect(ligne.querySelector('input[type="number"]')).toBeNull();
    expect(ligne.querySelector('.regles-seuil-valeur')?.textContent).toContain('8');
    expect(ligne.querySelector('a.regles-lien-parametres')?.getAttribute('href')).toBe(
      '/parametres?onglet=organisation',
    );
  });

  it('writes a level only on « Enregistrer »', async () => {
    page()['setNiveau'](CHARGE, 'OBLIGATOIRE');
    await fixture.whenStable();
    expect(constraintsApi['setNiveau']).not.toHaveBeenCalled();
    expect(page()['dirty']()).toBe(true);

    await page()['save']();

    expect(constraintsApi['setNiveau']).toHaveBeenCalledExactlyOnceWith(
      'equilibrerCharge',
      'OBLIGATOIRE',
    );
    expect(constraintsApi['setWeight']).not.toHaveBeenCalled();
  });

  it('chooses the stored level again as no change, and drops a weight typed before a level', () => {
    page()['setNiveau'](CHARGE, 'PREFERENCE_NORMALE');
    expect(page()['dirty']()).toBe(false);

    page()['setImportance'](CHARGE, 'FORTE');
    page()['setNiveau'](CHARGE, 'PREFERENCE_LEGERE');
    expect(page()['pendingCount']()).toBe(1);
  });

  it('offers a weight to a preference and none to a mandatory rule', async () => {
    await rendre({ regle: 'equilibrerCharge' });
    expect(racine().querySelector('.regles-panneau input[type="number"]')).not.toBeNull();

    page()['setNiveau'](CHARGE, 'OBLIGATOIRE');
    await fixture.whenStable();
    expect(racine().querySelector('.regles-panneau input[type="number"]')).toBeNull();
  });

  it('shows the four levels of an organisation rule, the stored one selected', () => {
    const groupe = racine().querySelector(
      'section[aria-labelledby="regles-bloc-organisation"] mat-button-toggle-group',
    ) as HTMLElement;
    const labels = Array.from(groupe.querySelectorAll('mat-button-toggle')).map((each) =>
      each.textContent!.trim(),
    );
    expect(labels).toEqual(['Obligatoire', 'Forte', 'Normale', 'Légère']);
    expect(page()['niveauOf'](CHARGE)).toBe('PREFERENCE_NORMALE');
  });

  it('keeps a protected rule on when the confirmation is refused', async () => {
    await openAdvanced();
    // The switch of the rule's row, flipped off by the click the event reports.
    const row = Array.from(racine().querySelectorAll('tbody tr')).find((each) =>
      each.textContent?.includes('Sécurité des mineurs'),
    );
    const toggle = fixture.debugElement
      .queryAll(By.directive(MatSlideToggle))
      .find((each) => row?.contains(each.nativeElement as Node))!
      .injector.get(MatSlideToggle);
    toggle.checked = false;
    const event = new MatSlideToggleChange(toggle, false);

    await page()['toggle'](PROTEGEE, event);

    expect(allowsDisabling).toHaveBeenCalledOnce();
    expect(event.source.checked).toBe(true);
    expect(page()['dirty']()).toBe(false);
  });

  it('writes an importance only on « Enregistrer », as its weight', async () => {
    page()['setImportance'](PREFERENCE, 'FORTE');
    expect(constraintsApi['setWeight']).not.toHaveBeenCalled();

    await page()['save']();

    expect(constraintsApi['setWeight']).toHaveBeenCalledExactlyOnceWith(
      'preserverBufferPolyvalents',
      25,
    );
  });

  it('relaunches by correction after consignes alone, by a full solve once anything else moved', async () => {
    await rendre();

    expect(page()['relance']()).toBeNull();
    page()['onConsigneChanged']('consignes');
    expect(page()['relance']()).toBe('correction');
    page()['onConsigneChanged']('ajustements');
    expect(page()['relance']()).toBe('calcul');
    page()['onConsigneChanged']('consignes');
    expect(page()['relance']()).toBe('calcul');
  });

  it('opens the panel of the rule the address names, on the rules tab', async () => {
    await rendre({ regle: 'equilibrerCharge' });

    expect(page()['onglet']()).toBe('regles');
    const panneau = racine().querySelector('.regles-panneau') as HTMLElement;
    expect(panneau.textContent).toContain('Charge équilibrée');
    expect(panneau.textContent).toContain('Cherchez qui est très au-dessus.');
  });

  it('opens the folded block for a rule it holds, so the rule asked for is never hidden', async () => {
    await rendre({ regle: 'posteDoitEtrePourvu' });

    await vi.waitFor(() =>
      expect(
        racine().querySelector('section[aria-labelledby="regles-bloc-avance"]'),
      ).not.toBeNull(),
    );
  });

  it('names a weight outside the three positions « personnalisée »', async () => {
    await rendre({ onglet: 'regles' }, [{ ...CHARGE, poids: 7 }]);

    expect(racine().querySelector('.regles-personnalise')?.textContent).toContain('7');
  });

  /** ADR 0057: a hard rule is not dosed, but a weight stored before stays visible and can be undone. */
  it('shows a hard rule’s own weight and brings it back to 1 on « Enregistrer »', async () => {
    await rendre({ regle: 'posteDoitEtrePourvu' }, [{ ...PLACES, poids: 3 }, HEBDO]);
    await vi.waitFor(() => expect(racine().textContent).toContain('Places pourvues'));

    const ligne = Array.from(racine().querySelectorAll('tbody tr')).find((each) =>
      each.textContent?.includes('Places pourvues'),
    ) as HTMLElement;
    expect(ligne.querySelector('.regles-personnalise')?.textContent).toContain(
      'poids personnalisé 3',
    );
    const panneau = racine().querySelector('.regles-panneau') as HTMLElement;
    expect(panneau.textContent).toContain('Poids personnalisé');
    expect(panneau.querySelector('input[type="number"]')).toBeNull();
    const revenir = Array.from(panneau.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Revenir à 1',
    ) as HTMLButtonElement;

    revenir.click();
    await fixture.whenStable();
    expect(ligne.querySelector('.regles-personnalise')).toBeNull();
    expect(revenir.disabled).toBe(true);
    await page()['save']();

    expect(constraintsApi['setWeight']).toHaveBeenCalledExactlyOnceWith('posteDoitEtrePourvu', 1);
  });

  it('says nothing of the weight of a hard rule at 1', async () => {
    await rendre({ regle: 'posteDoitEtrePourvu' });
    await vi.waitFor(() => expect(racine().querySelector('.regles-panneau')).not.toBeNull());

    expect(racine().querySelector('.regles-personnalise')).toBeNull();
    expect(racine().querySelector('.regles-panneau')?.textContent).not.toContain(
      'Poids personnalisé',
    );
  });

  it('offers to switch a legal rule back on when it was left off', async () => {
    await rendre({ onglet: 'regles' }, [{ ...HEBDO, actif: false }, CHARGE]);

    const bouton = racine().querySelector(
      'section[aria-labelledby="regles-bloc-legal"] tbody th button.mat-mdc-button',
    ) as HTMLButtonElement;
    expect(bouton.textContent).toContain('Réactiver');
    bouton.click();
    await fixture.whenStable();
    await page()['save']();

    expect(constraintsApi['setActive']).toHaveBeenCalledExactlyOnceWith(
      'dureeHebdomadaireMax',
      true,
    );
  });

  it('shows no custom weight on a medium rule made mandatory: its preference weight is kept, not applied', async () => {
    await rendre({ regle: 'equilibrerCharge' }, [
      { ...CHARGE, poids: 7, niveauChoisi: 'OBLIGATOIRE' },
    ]);

    expect(racine().querySelector('.regles-personnalise')).toBeNull();
    expect(racine().querySelector('.regles-panneau')?.textContent).not.toContain(
      'Poids personnalisé',
    );
  });

  it('asks before leaving with changes pending, and not otherwise', async () => {
    await expect(fixture.componentInstance.canLeave()).resolves.toBe(true);
    expect(ask).not.toHaveBeenCalled();

    page()['setNiveau'](CHARGE, 'OBLIGATOIRE');

    await fixture.componentInstance.canLeave();
    expect(ask).toHaveBeenCalledOnce();
  });
});
