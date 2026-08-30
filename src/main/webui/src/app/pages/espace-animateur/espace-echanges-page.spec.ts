// Orchestration of the échange form of the espace animateur (issue #165).
//
// `espace-animateur.service.spec.ts` covers the transport and
// `echange-brouillon.spec.ts` the pure draft rules; what was untested is the
// page that ties them together — picking a colleague, turning a picked seat
// into a draft, submitting the batch, and how every outcome is announced to an
// animateur who is not an admin and has no console to read.
//
// The first half creates the component without rendering it. The second one
// renders it, for one rule that lives entirely in the template: once the foire
// is closed, every action must disappear — proposing, accepting, declining,
// cancelling — while the lists stay readable. A stale button there lets an
// animateur act on a planning the organisation considers frozen.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { NotificationService } from '../../core/notification.service';
import {
  DemandeEchangeView,
  EspaceAnimateurView,
  PosteAnimateurView,
  NatureEchange,
  StatutDemandeEchange,
  SuggestionEchangeView,
  SuggestionsEchangeView
} from '../../core/models';
import { BrouillonDemande } from './echange-brouillon';
import { EspaceEchangesPage } from './espace-echanges-page';

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    creneauId: 1,
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    standId: 'tir',
    standNom: 'Tir à la corde',
    coequipiers: [],
    ...overrides
  };
}

function vue(overrides: Partial<EspaceAnimateurView> = {}): EspaceAnimateurView {
  return {
    joursRepos: [],
    animateurId: 'alice',
    prenom: 'Alice',
    nom: 'Martin',
    publieLe: '2026-07-01T10:00:00Z',
    foireOuverte: true,
    postes: [poste()],
    collegues: [{ id: 'bob', nomComplet: 'Bob Durand' }],
    statutConfirmation: 'NON_VU',
    confirmeLe: null,
    foireOuvreLe: null,
    foireFermeLe: null,
    ...overrides
  };
}

function demande(id: string, statut: StatutDemandeEchange, overrides: Partial<DemandeEchangeView> = {}): DemandeEchangeView {
  return {
    id,
    creneauId: 1,
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    standId: 'tir',
    standNom: 'Tir à la corde',
    demandeurId: 'alice',
    demandeurNom: 'Alice Martin',
    cibleId: 'bob',
    cibleNom: 'Bob Durand',
    creneauCibleId: null,
    dateCible: null,
    heureDebutCible: null,
    heureFinCible: null,
    standCibleId: null,
    standCibleNom: null,
    motif: null,
    statut,
    prevalidationOk: true,
    ...overrides
  } as DemandeEchangeView;
}

function suggestions(
  trouvees: SuggestionEchangeView[],
  overrides: Partial<SuggestionsEchangeView> = {}
): SuggestionsEchangeView {
  return {
    creneauId: 1,
    standId: 'tir',
    optionsEligibles: trouvees.length,
    optionsEvaluees: trouvees.length,
    listeTronquee: false,
    suggestions: trouvees,
    ...overrides
  };
}

function suggestion(
  animateurId: string,
  nature: NatureEchange,
  overrides: Partial<SuggestionEchangeView> = {}
): SuggestionEchangeView {
  return {
    animateurId,
    nomComplet: `${animateurId} Durand`,
    nature,
    creneauCibleId: null,
    dateCible: null,
    heureDebutCible: null,
    heureFinCible: null,
    standCibleId: null,
    standCibleNom: null,
    ...overrides
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  posteChoisi: WritableSignal<PosteAnimateurView | null>;
  cibleId: WritableSignal<string>;
  posteCibleChoisi: WritableSignal<PosteAnimateurView | null>;
  postesCollegue: Signal<PosteAnimateurView[]>;
  motif: WritableSignal<string>;
  brouillons: WritableSignal<BrouillonDemande[]>;
  envoiEnCours: Signal<boolean>;
  formulaireComplet: Signal<boolean>;
  foireOuverte: Signal<boolean>;
  demandes: Signal<{ id: string; statutLabel: string; statutClasse: string }[]>;
  recuesEnAttente: Signal<{ id: string }[]>;
  choisirCible: (cibleId: string) => Promise<void>;
  choisirPoste: (poste: PosteAnimateurView | null) => void;
  chercherRemplacants: () => Promise<void>;
  retenirSuggestion: (suggestion: SuggestionEchangeView) => Promise<void>;
  suggestionsDe: (nature: NatureEchange) => SuggestionEchangeView[];
  suggestions: WritableSignal<SuggestionsEchangeView | null>;
  rechercheEnCours: Signal<boolean>;
  suggestionsTronquees: Signal<boolean>;
  ajouter: () => void;
  retirer: (index: number) => void;
  soumettre: () => Promise<void>;
  annuler: (demande: { id: string }) => Promise<void>;
  accorder: (demande: { id: string }) => Promise<void>;
  decliner: (demande: { id: string }) => Promise<void>;
};

describe('EspaceEchangesPage', () => {
  const espaceVue = signal<EspaceAnimateurView | null>(vue());
  const espaceDemandes = signal<DemandeEchangeView[]>([]);
  const espaceRecues = signal<DemandeEchangeView[]>([]);
  const espace = {
    vue: espaceVue,
    demandes: espaceDemandes,
    demandesRecues: espaceRecues,
    postesCollegue: vi.fn(),
    suggestionsEchange: vi.fn(),
    soumettre: vi.fn(),
    annuler: vi.fn(),
    accorderRecue: vi.fn(),
    declinerRecue: vi.fn()
  };
  const notifications = { notify: vi.fn() };

  beforeEach(() => {
    espaceVue.set(vue());
    espaceDemandes.set([]);
    espaceRecues.set([]);
    for (const stub of [
      espace.postesCollegue,
      espace.suggestionsEchange,
      espace.soumettre,
      espace.annuler,
      espace.accorderRecue,
      espace.declinerRecue
    ]) {
      stub.mockReset();
    }
    notifications.notify.mockReset();
    espace.postesCollegue.mockResolvedValue([]);
    espace.suggestionsEchange.mockResolvedValue(suggestions([]));
    espace.soumettre.mockResolvedValue([]);
    espace.annuler.mockResolvedValue(undefined);
    espace.accorderRecue.mockResolvedValue(undefined);
    espace.declinerRecue.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: EspaceAnimateurService, useValue: espace },
        { provide: NotificationService, useValue: notifications }
      ]
    });
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(EspaceEchangesPage).componentInstance as unknown as PageInternals;
  }

  // « Qui peut me remplacer ? » — the animateur who does not want a créneau and
  // has nobody in mind. The search only proposes names: it creates no demande,
  // and the picked colleague still lands in the ordinary form.
  describe('searching who could take the seat over', () => {
    it('searches on the picked seat and keeps the answer', async () => {
      const page = createPage();
      page.choisirPoste(poste({ creneauId: 7, standId: 'quilles' }));
      espace.suggestionsEchange.mockResolvedValue(suggestions([suggestion('bob', 'LIBERE')]));

      await page.chercherRemplacants();

      expect(espace.suggestionsEchange).toHaveBeenCalledExactlyOnceWith(7, 'quilles');
      expect(page.suggestions()?.suggestions).toHaveLength(1);
      expect(page.rechercheEnCours()).toBe(false);
    });

    it('asks for nothing while no seat is picked', async () => {
      const page = createPage();

      await page.chercherRemplacants();

      expect(espace.suggestionsEchange).not.toHaveBeenCalled();
      expect(page.suggestions()).toBeNull();
    });

    // A list found for Monday says nothing about Tuesday: leaving it on screen
    // would answer a question nobody asked.
    it('drops the answer as soon as another seat is picked', async () => {
      const page = createPage();
      page.choisirPoste(poste());
      espace.suggestionsEchange.mockResolvedValue(suggestions([suggestion('bob', 'LIBERE')]));
      await page.chercherRemplacants();

      page.choisirPoste(poste({ creneauId: 2 }));

      expect(page.suggestions()).toBeNull();
    });

    it('reports a failed search instead of leaving a stale list', async () => {
      const page = createPage();
      page.choisirPoste(poste());
      espace.suggestionsEchange.mockRejectedValue(new Error('Aucun planning persisté.'));

      await page.chercherRemplacants();

      expect(page.suggestions()).toBeNull();
      expect(page.rechercheEnCours()).toBe(false);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'Aucun planning persisté.' })
      );
    });

    // A truncated search is the best of what was tried, never « nobody else can ».
    it('says when the search stopped short of the whole roster', async () => {
      const page = createPage();
      page.choisirPoste(poste());
      espace.suggestionsEchange.mockResolvedValue(
        suggestions(
          [suggestion('bob', 'CROISE', { standCibleId: 'quilles', standCibleNom: 'Quilles' })],
          { optionsEligibles: 137, optionsEvaluees: 20, listeTronquee: true }
        )
      );

      await page.chercherRemplacants();

      expect(page.suggestionsTronquees()).toBe(true);
    });

    it('fills the colleague field from the retained suggestion, nothing more', async () => {
      const page = createPage();
      espace.postesCollegue.mockResolvedValue([poste({ creneauId: 9, standId: 'quilles' })]);

      await page.retenirSuggestion(suggestion('bob', 'LIBERE'));

      expect(page.cibleId()).toBe('bob');
      // A plain échange: the retained name never preselects a seat in return.
      expect(page.posteCibleChoisi()).toBeNull();
      expect(espace.soumettre).not.toHaveBeenCalled();
    });

    // The cross-day family is the whole point of an EXCHANGE assistant: the
    // retained suggestion has to carry the seat wanted in return all the way
    // into the demande, or it degrades into a plain hand-over.
    it('preselects the seat wanted in return for a cross-day trade', async () => {
      const page = createPage();
      const mardi = poste({ creneauId: 9, standId: 'quilles', date: '2026-08-02' });
      espace.postesCollegue.mockResolvedValue([poste({ creneauId: 3, standId: 'tir' }), mardi]);

      await page.retenirSuggestion(
        suggestion('bob', 'DIRIGE', {
          creneauCibleId: 9,
          standCibleId: 'quilles',
          dateCible: '2026-08-02',
          standCibleNom: 'Quilles'
        })
      );

      expect(page.cibleId()).toBe('bob');
      // The very option object the select holds, not a rebuilt lookalike.
      expect(page.posteCibleChoisi()).toBe(mardi);
    });

    // A seat the colleague no longer holds (the planning moved between the
    // search and the click) must leave the form plain rather than silently
    // sending a directed demande on a seat nobody owns.
    it('falls back to a plain swap when the seat wanted in return is gone', async () => {
      const page = createPage();
      espace.postesCollegue.mockResolvedValue([poste({ creneauId: 3, standId: 'tir' })]);

      await page.retenirSuggestion(
        suggestion('bob', 'DIRIGE', { creneauCibleId: 9, standCibleId: 'quilles' })
      );

      expect(page.posteCibleChoisi()).toBeNull();
    });

    it('splits the answer into the three families the espace lists apart', async () => {
      const page = createPage();
      page.choisirPoste(poste());
      espace.suggestionsEchange.mockResolvedValue(
        suggestions([
          suggestion('bob', 'LIBERE'),
          suggestion('carole', 'DIRIGE', { creneauCibleId: 9, standCibleId: 'quilles' }),
          suggestion('david', 'CROISE', { standCibleId: 'quilles', standCibleNom: 'Quilles' })
        ])
      );

      await page.chercherRemplacants();

      expect(page.suggestionsDe('LIBERE').map((s) => s.animateurId)).toEqual(['bob']);
      expect(page.suggestionsDe('DIRIGE').map((s) => s.animateurId)).toEqual(['carole']);
      expect(page.suggestionsDe('CROISE').map((s) => s.animateurId)).toEqual(['david']);
    });
  });

  describe('picking the colleague', () => {
    it('loads the colleague seats so the "wanted in return" picker has real options', async () => {
      const page = createPage();
      espace.postesCollegue.mockResolvedValue([poste({ creneauId: 9, standId: 'quilles' })]);

      await page.choisirCible('bob');

      expect(espace.postesCollegue).toHaveBeenCalledExactlyOnceWith('bob');
      expect(page.postesCollegue()).toHaveLength(1);
      expect(page.cibleId()).toBe('bob');
    });

    it('clears the previous colleague seats before loading the new ones', async () => {
      const page = createPage();
      espace.postesCollegue.mockResolvedValue([poste({ creneauId: 9 })]);
      await page.choisirCible('bob');
      page.posteCibleChoisi.set(poste({ creneauId: 9 }));

      espace.postesCollegue.mockResolvedValue([]);
      await page.choisirCible('carole');

      expect(page.postesCollegue()).toEqual([]);
      expect(page.posteCibleChoisi()).toBeNull();
    });

    it('asks for nothing when the colleague is unpicked', async () => {
      const page = createPage();

      await page.choisirCible('');

      expect(espace.postesCollegue).not.toHaveBeenCalled();
      expect(page.cibleId()).toBe('');
    });

    // No persisted planning, a network hiccup: the picker stays empty and the
    // demande falls back to its plain "same créneau" semantics rather than
    // showing the animateur an error they can do nothing about.
    it('leaves the picker empty and silent when the seats cannot be loaded', async () => {
      const page = createPage();
      espace.postesCollegue.mockRejectedValue(new Error('Aucun planning persisté.'));

      await page.choisirCible('bob');

      expect(page.postesCollegue()).toEqual([]);
      expect(notifications.notify).not.toHaveBeenCalled();
      expect(page.cibleId()).toBe('bob');
    });
  });

  describe('building the batch', () => {
    it('refuses to add anything while the form is incomplete', () => {
      const page = createPage();

      page.ajouter();
      expect(page.brouillons()).toEqual([]);

      page.posteChoisi.set(poste());
      expect(page.formulaireComplet()).toBe(false);
      page.ajouter();
      expect(page.brouillons()).toEqual([]);
    });

    it('adds a plain demande and resets the whole form behind it', () => {
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');
      page.motif.set('mariage');

      page.ajouter();

      expect(page.brouillons()).toHaveLength(1);
      expect(page.brouillons()[0]).toMatchObject({
        creneauId: 1,
        standId: 'tir',
        cibleId: 'bob',
        motif: 'mariage',
        creneauCibleId: null,
        standCibleId: null
      });
      expect(page.posteChoisi()).toBeNull();
      expect(page.cibleId()).toBe('');
      expect(page.motif()).toBe('');
      expect(page.postesCollegue()).toEqual([]);
    });

    it('resolves the colleague display name from the espace view', () => {
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');

      page.ajouter();

      expect(page.brouillons()[0].cibleNom).toBe('Bob Durand');
    });

    it('falls back to the colleague id when the view does not name them', () => {
      espaceVue.set(vue({ collegues: [] }));
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');

      page.ajouter();

      expect(page.brouillons()[0].cibleNom).toBe('bob');
    });

    it('records an empty motif as absent rather than as an empty string', () => {
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');
      page.motif.set('');

      page.ajouter();

      expect(page.brouillons()[0].motif).toBeNull();
    });

    // Setting both sides is what makes the exchange directed; a picked seat
    // must carry its créneau AND its stand, or the server sees a plain demande.
    it('makes the exchange directed when a seat is wanted in return', () => {
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');
      page.posteCibleChoisi.set(poste({ creneauId: 9, standId: 'quilles', standNom: 'Quilles' }));

      page.ajouter();

      expect(page.brouillons()[0]).toMatchObject({ creneauCibleId: 9, standCibleId: 'quilles' });
      expect(page.brouillons()[0].creneauCibleLabel).toContain('Quilles');
    });

    it('labels the offered seat with its date, hours and stand', () => {
      const page = createPage();
      page.posteChoisi.set(poste({ date: '2026-08-03', heureDebut: '14:00', heureFin: '18:00' }));
      page.cibleId.set('bob');

      page.ajouter();

      expect(page.brouillons()[0].creneauLabel).toContain('2026-08-03');
      expect(page.brouillons()[0].creneauLabel).toContain('14:00');
      expect(page.brouillons()[0].standNom).toBe('Tir à la corde');
    });

    it('drops one line of the batch without touching the others', () => {
      const page = createPage();
      for (const creneauId of [1, 2, 3]) {
        page.posteChoisi.set(poste({ creneauId }));
        page.cibleId.set('bob');
        page.ajouter();
      }

      page.retirer(1);

      expect(page.brouillons().map((brouillon) => brouillon.creneauId)).toEqual([1, 3]);
    });
  });

  describe('submitting the batch', () => {
    function withOneDraft(): PageInternals {
      const page = createPage();
      page.posteChoisi.set(poste());
      page.cibleId.set('bob');
      page.ajouter();
      return page;
    }

    it('sends nothing when the batch is empty', async () => {
      const page = createPage();

      await page.soumettre();

      expect(espace.soumettre).not.toHaveBeenCalled();
    });

    it('sends the batch as new demandes and clears it', async () => {
      const page = withOneDraft();

      await page.soumettre();

      expect(espace.soumettre).toHaveBeenCalledExactlyOnceWith([
        expect.objectContaining({ creneauId: 1, standId: 'tir', cibleId: 'bob' })
      ]);
      expect(page.brouillons()).toEqual([]);
      expect(page.envoiEnCours()).toBe(false);
    });

    it('confirms a fully feasible batch without an alarm', async () => {
      const page = withOneDraft();
      espace.soumettre.mockResolvedValue([demande('d1', 'PROPOSEE')]);

      await page.soumettre();

      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    // An infeasible demande is still submitted for arbitration, but the
    // animateur has to be told, in business words, that it is unlikely to pass.
    it('warns how many demandes the prevalidation rejected, without dropping them', async () => {
      const page = withOneDraft();
      espace.soumettre.mockResolvedValue([
        demande('d1', 'PROPOSEE', { prevalidationOk: false }),
        demande('d2', 'PROPOSEE', { prevalidationOk: true }),
        demande('d3', 'PROPOSEE', { prevalidationOk: false })
      ]);

      await page.soumettre();

      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'warning', message: expect.stringContaining('2') })
      );
    });

    // `prevalidationOk` is null while not evaluated, which is not a rejection.
    it('does not count an unevaluated prevalidation as a rejection', async () => {
      const page = withOneDraft();
      espace.soumettre.mockResolvedValue([demande('d1', 'PROPOSEE', { prevalidationOk: null })]);

      await page.soumettre();

      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('keeps the batch and reports the failure when the submission is refused', async () => {
      const page = withOneDraft();
      espace.soumettre.mockRejectedValue(new Error('Foire fermée.'));

      await page.soumettre();

      expect(page.brouillons()).toHaveLength(1);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: expect.stringContaining('Foire fermée.') })
      );
      expect(page.envoiEnCours()).toBe(false);
    });

    it('refuses a second submission while the first is still in flight', async () => {
      const page = withOneDraft();
      let release!: (value: DemandeEchangeView[]) => void;
      espace.soumettre.mockReturnValue(
        new Promise<DemandeEchangeView[]>((resolve) => {
          release = resolve;
        })
      );

      const first = page.soumettre();
      await Promise.resolve();
      expect(page.envoiEnCours()).toBe(true);

      await page.soumettre();
      expect(espace.soumettre).toHaveBeenCalledOnce();

      release([]);
      await first;
      expect(page.envoiEnCours()).toBe(false);
    });
  });

  describe('the history and the demandes received', () => {
    it('decorates every demande with its statut label and class', () => {
      espaceDemandes.set([demande('d1', 'PROPOSEE'), demande('d2', 'ACCEPTEE')]);
      const page = createPage();

      expect(page.demandes()).toHaveLength(2);
      expect(page.demandes()[0].statutLabel).not.toBe('');
      expect(page.demandes()[0].statutClasse).not.toBe(page.demandes()[1].statutClasse);
    });

    // Only the ones still waiting for MY agreement are actionable; the rest of
    // what targets me is history and must not offer buttons.
    it('keeps only the demandes still waiting for my agreement', () => {
      espaceRecues.set([
        demande('d1', 'EN_ATTENTE_CIBLE'),
        demande('d2', 'PROPOSEE'),
        demande('d3', 'REFUSEE_CIBLE'),
        demande('d4', 'EN_ATTENTE_CIBLE')
      ]);
      const page = createPage();

      expect(page.recuesEnAttente().map((row) => row.id)).toEqual(['d1', 'd4']);
    });

    it('treats a closed foire as read-only, and an unloaded espace as open', () => {
      espaceVue.set(vue({ foireOuverte: false }));
      expect(createPage().foireOuverte()).toBe(false);

      espaceVue.set(null);
      expect(createPage().foireOuverte()).toBe(true);
    });
  });

  describe('acting on a demande', () => {
    it('confirms an agreement and says the organisation will arbitrate', async () => {
      const page = createPage();

      await page.accorder({ id: 'd1' });

      expect(espace.accorderRecue).toHaveBeenCalledExactlyOnceWith('d1');
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('confirms a refusal and says the colleague is informed', async () => {
      const page = createPage();

      await page.decliner({ id: 'd1' });

      expect(espace.declinerRecue).toHaveBeenCalledExactlyOnceWith('d1');
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('confirms a withdrawal', async () => {
      const page = createPage();

      await page.annuler({ id: 'd1' });

      expect(espace.annuler).toHaveBeenCalledExactlyOnceWith('d1');
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    // An animateur has no console: a rejected action that says nothing looks
    // like a click that did not register.
    it.each([
      ['accorder', (page: PageInternals) => page.accorder({ id: 'd1' }), espace.accorderRecue],
      ['decliner', (page: PageInternals) => page.decliner({ id: 'd1' }), espace.declinerRecue],
      ['annuler', (page: PageInternals) => page.annuler({ id: 'd1' }), espace.annuler]
    ])('reports a refused %s instead of failing silently', async (_name, act, stub) => {
      const page = createPage();
      stub.mockRejectedValue(new Error('Demande déjà tranchée.'));

      await act(page);

      expect(notifications.notify).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ variant: 'error', message: expect.stringContaining('Demande déjà tranchée.') })
      );
    });
  });
});

describe('EspaceEchangesPage rendering', () => {
  let fixture: ComponentFixture<EspaceEchangesPage>;
  const espaceVue = signal<EspaceAnimateurView | null>(vue());
  const espaceDemandes = signal<DemandeEchangeView[]>([]);
  const espaceRecues = signal<DemandeEchangeView[]>([]);
  let espace: {
    postesCollegue: ReturnType<typeof vi.fn>;
    soumettre: ReturnType<typeof vi.fn>;
    annuler: ReturnType<typeof vi.fn>;
    accorderRecue: ReturnType<typeof vi.fn>;
    declinerRecue: ReturnType<typeof vi.fn>;
  };

  async function rendre(): Promise<void> {
    espace = {
      postesCollegue: vi.fn(async () => []),
      soumettre: vi.fn(async () => undefined),
      annuler: vi.fn(async () => undefined),
      accorderRecue: vi.fn(async () => undefined),
      declinerRecue: vi.fn(async () => undefined)
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: EspaceAnimateurService,
          useValue: { vue: espaceVue, demandes: espaceDemandes, demandesRecues: espaceRecues, ...espace }
        },
        { provide: NotificationService, useValue: { notify: vi.fn() } }
      ]
    });
    fixture = TestBed.createComponent(EspaceEchangesPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function texte(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  function bouton(libelle: string): HTMLButtonElement | undefined {
    return Array.from(racine().querySelectorAll('button')).find((each) => each.textContent!.includes(libelle));
  }

  beforeEach(() => {
    espaceVue.set(vue());
    espaceDemandes.set([]);
    espaceRecues.set([]);
  });

  it('says there is nothing yet rather than showing an empty list', async () => {
    await rendre();

    expect(texte()).toContain('Aucune demande pour le moment.');
  });

  it('shows the form while the foire is open', async () => {
    await rendre();

    expect(racine().querySelector('.espace-form')).not.toBeNull();
    expect(racine().querySelector('.espace-foire-fermee')).toBeNull();
    // Nothing picked yet: adding to the list must not be possible.
    expect(bouton('Ajouter à la liste')!.disabled).toBe(true);
  });

  it('withdraws every action once the foire is closed, and says so', async () => {
    espaceVue.set(vue({ foireOuverte: false }));
    espaceDemandes.set([demande('d1', 'PROPOSEE')]);
    espaceRecues.set([demande('d2', 'EN_ATTENTE_CIBLE', { demandeurId: 'bob', cibleId: 'alice' })]);
    await rendre();

    expect(racine().querySelector('.espace-foire-fermee')).not.toBeNull();
    expect(racine().querySelector('.espace-form')).toBeNull();
    expect(bouton('Annuler cette demande')).toBeUndefined();
    expect(bouton("Je suis d'accord")).toBeUndefined();
    expect(bouton('Décliner')).toBeUndefined();
    // The history stays readable: only acting is closed.
    expect(texte()).toContain('Mes demandes');
    expect(texte()).toContain('Bob Durand');
  });

  it('lists a received request with its two seats, and both answers', async () => {
    espaceRecues.set([
      demande('d2', 'EN_ATTENTE_CIBLE', {
        demandeurId: 'bob',
        demandeurNom: 'Bob Durand',
        cibleId: 'alice',
        creneauCibleId: 2,
        dateCible: '2026-08-02',
        heureDebutCible: '14:00',
        heureFinCible: '18:00',
        standCibleNom: 'Molkky',
        motif: 'Mariage'
      })
    ]);
    await rendre();

    expect(texte()).toContain('vous propose de reprendre');
    expect(texte()).toContain('contre votre créneau');
    expect(texte()).toContain('Molkky');
    expect(texte()).toContain('Mariage');

    bouton("Je suis d'accord")!.click();
    await fixture.whenStable();
    expect(espace.accorderRecue).toHaveBeenCalled();
  });

  it('declines a received request through the other button', async () => {
    espaceRecues.set([demande('d2', 'EN_ATTENTE_CIBLE', { demandeurId: 'bob', cibleId: 'alice' })]);
    await rendre();

    bouton('Décliner')!.click();
    await fixture.whenStable();

    expect(espace.declinerRecue).toHaveBeenCalled();
    expect(espace.accorderRecue).not.toHaveBeenCalled();
  });

  it('warns that a pending request would break the planning, without hiding that it was sent anyway', async () => {
    espaceDemandes.set([
      demande('d1', 'PROPOSEE', {
        prevalidationOk: false,
        contraintesViolees: ['Repos quotidien insuffisant']
      } as Partial<DemandeEchangeView>)
    ]);
    await rendre();

    const alerte = racine().querySelector('.espace-demande-alerte')!;
    expect(alerte.textContent!).toContain('Repos quotidien insuffisant');
    expect(alerte.textContent!).toContain("l'organisation tranchera");
  });

  it('keeps the warning off a request that is already settled', async () => {
    espaceDemandes.set([
      demande('d1', 'REFUSEE', {
        prevalidationOk: false,
        contraintesViolees: ['Repos quotidien insuffisant'],
        commentaireAdmin: 'Impossible ce week-end.'
      } as Partial<DemandeEchangeView>)
    ]);
    await rendre();

    // Nothing left to arbitrate: the warning would only be noise.
    expect(racine().querySelector('.espace-demande-alerte')).toBeNull();
    expect(bouton('Annuler cette demande')).toBeUndefined();
    // The organisation's answer, however, must be shown.
    expect(texte()).toContain('Impossible ce week-end.');
  });

  it('lets a pending request be cancelled, and names its status', async () => {
    espaceDemandes.set([demande('d1', 'PROPOSEE')]);
    await rendre();

    expect(racine().querySelector('.espace-statut')!.textContent!.trim()).not.toBe('');
    bouton('Annuler cette demande')!.click();
    await fixture.whenStable();

    expect(espace.annuler).toHaveBeenCalled();
  });
});
