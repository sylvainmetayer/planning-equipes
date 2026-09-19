// Publishing (retour utilisateur, #333). Tens of seconds used to pass with no
// sign at all, on the one action that writes to real people — and the natural
// reflex in front of a screen that says nothing is to click again. The exports
// share the panel: what they say goes to the page's output panel, and while
// one runs the page names it as the reason its actions are locked.

import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PublicationPanel } from './publication-panel';

type PanelInternals = {
  busy: Signal<boolean>;
  publishable: Signal<boolean>;
  recipientsInFlight: Signal<number>;
  exportBusy: Signal<boolean>;
  publish: () => Promise<void>;
  exportGlobalPdf: () => Promise<void>;
  exportBundle: () => Promise<void>;
  exportDiff: () => Promise<void>;
  rows: Signal<{ animateurId: string }[]>;
  minorCount: Signal<number>;
  notifiedCount: Signal<number>;
  minorHidden: Signal<boolean>;
  isExcluded: (animateurId: string) => boolean;
  toggleExclusion: (animateurId: string, prevenir: boolean) => void;
  toggleMinorFilter: (masquer: boolean) => void;
  chooseSort: (tri: 'nom' | 'ampleur') => void;
  reloadPreview: () => Promise<void>;
};

describe('PublicationPanel', () => {
  const planningApi = {
    publicationPreview: vi.fn(),
    publish: vi.fn(),
    exportGlobalPdf: vi.fn(),
    exportBundle: vi.fn(),
    exportPublicationDiff: vi.fn(),
  };
  const planningState = { require: vi.fn() };
  const confirm = { ask: vi.fn() };
  const editingLocked = signal(false);

  const apercuPret = {
    jamaisPublie: false,
    planVide: false,
    solveEnCours: false,
    dernierePublicationLe: null,
    nombreConcernes: 3,
    journeesNonValidees: 0,
    destinataires: [],
  };

  let fixture: ComponentFixture<PublicationPanel>;
  /** Everything the panel said to the page's output panel, in order. */
  let messages: string[];
  let exportBusyChanges: boolean[];

  beforeEach(() => {
    editingLocked.set(false);
    for (const stub of [
      planningApi.publicationPreview,
      planningApi.publish,
      planningApi.exportGlobalPdf,
      planningApi.exportBundle,
      planningApi.exportPublicationDiff,
      planningState.require,
      confirm.ask,
    ]) {
      stub.mockReset();
    }
    planningApi.publicationPreview.mockResolvedValue({});
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
        { provide: PlanningStateService, useValue: planningState },
        { provide: ConfirmService, useValue: confirm },
        { provide: SolverJobService, useValue: { editingLocked: () => editingLocked() } },
      ],
    });
  });

  function createPanel(): PanelInternals {
    fixture = TestBed.createComponent(PublicationPanel);
    messages = [];
    exportBusyChanges = [];
    fixture.componentInstance.reported.subscribe((message) => messages.push(message));
    fixture.componentInstance.exportBusyChange.subscribe((busy) => exportBusyChanges.push(busy));
    return fixture.componentInstance as unknown as PanelInternals;
  }

  /** A publication that hangs until the test lets it finish. */
  function envoiSuspendu(): { terminer: () => void } {
    let finish = (): void => undefined;
    planningApi.publish.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({ envoyes: 3, sansEmail: [], echecs: [], differes: [] });
        }),
    );
    return { terminer: () => finish() };
  }

  async function panelPret(): Promise<PanelInternals> {
    planningApi.publicationPreview.mockResolvedValue(apercuPret);
    const panel = createPanel();
    await vi.waitFor(() => expect(panel.publishable()).toBe(true));
    return panel;
  }

  it('reads who is concerned when it appears', async () => {
    createPanel();

    await vi.waitFor(() => expect(planningApi.publicationPreview).toHaveBeenCalledOnce());
  });

  it('says a send is under way, and how many people it concerns', async () => {
    confirm.ask.mockResolvedValue(true);
    const envoi = envoiSuspendu();
    const panel = await panelPret();

    const publication = panel.publish();
    await vi.waitFor(() => expect(planningApi.publish).toHaveBeenCalledTimes(1));

    expect(panel.busy()).toBe(true);
    // The count is frozen at the start: the preview reloads at the end and
    // would otherwise fall to zero in the middle of the sentence.
    expect(panel.recipientsInFlight()).toBe(3);

    envoi.terminer();
    await publication;
    expect(panel.busy()).toBe(false);
    // And the preview was re-read once the mail went out.
    expect(planningApi.publicationPreview).toHaveBeenCalledTimes(2);
  });

  it('is inert while the send is in flight, rather than firing a second wave', async () => {
    confirm.ask.mockResolvedValue(true);
    const envoi = envoiSuspendu();
    const panel = await panelPret();
    const publication = panel.publish();
    await vi.waitFor(() => expect(planningApi.publish).toHaveBeenCalledTimes(1));

    await panel.publish();

    // Real mail to real people: a second click must not send it twice.
    expect(planningApi.publish).toHaveBeenCalledTimes(1);
    envoi.terminer();
    await publication;
  });

  it('is already inert while the confirmation is on screen', async () => {
    // The window the guard used to leave open: the flag was raised only after
    // the confirmation, so a second click opened a second dialog — and two
    // confirmations meant two waves of mail.
    let confirmer = (): void => undefined;
    confirm.ask.mockImplementation(
      () =>
        new Promise<boolean>((resolve) => {
          confirmer = () => resolve(true);
        }),
    );
    const envoi = envoiSuspendu();
    const panel = await panelPret();

    const publication = panel.publish();
    await vi.waitFor(() => expect(confirm.ask).toHaveBeenCalledTimes(1));
    expect(panel.busy()).toBe(true);

    await panel.publish();
    expect(confirm.ask).toHaveBeenCalledTimes(1);

    confirmer();
    await vi.waitFor(() => expect(planningApi.publish).toHaveBeenCalledTimes(1));
    envoi.terminer();
    await publication;
  });

  it('releases the button when the confirmation is declined', async () => {
    confirm.ask.mockResolvedValue(false);
    const panel = await panelPret();

    await panel.publish();

    expect(planningApi.publish).not.toHaveBeenCalled();
    expect(panel.busy()).toBe(false);
  });

  it('releases the button when the send fails', async () => {
    confirm.ask.mockResolvedValue(true);
    planningApi.publish.mockRejectedValue(new Error('SMTP injoignable'));
    const panel = await panelPret();

    await panel.publish();

    // Otherwise a failed send would leave the action locked until reload,
    // with no way to try again.
    expect(panel.busy()).toBe(false);
    expect(messages.at(-1)).toContain('SMTP injoignable');
  });

  it('stays silent rather than announcing a count it could not read', async () => {
    planningApi.publicationPreview.mockRejectedValue(new Error('HTTP 500'));
    const panel = createPanel();

    await vi.waitFor(() => expect(planningApi.publicationPreview).toHaveBeenCalled());
    await fixture.whenStable();

    expect(panel.publishable()).toBe(false);
    expect(fixture.componentInstance.preview()).toBeNull();
  });

  describe('the exports', () => {
    it('tells the page an export is being built, then what came of it', async () => {
      planningApi.exportGlobalPdf.mockResolvedValue('Téléchargement démarré.');
      const panel = createPanel();

      await panel.exportGlobalPdf();

      expect(exportBusyChanges).toEqual([true, false]);
      expect(messages).toEqual(['Construction du PDF global...', 'Téléchargement démarré.']);
      expect(panel.exportBusy()).toBe(false);
    });

    it('sends the planning the browser holds for the per-animateur archive', async () => {
      planningState.require.mockResolvedValue({ postes: [] });
      planningApi.exportBundle.mockResolvedValue('Téléchargement démarré.');
      const panel = createPanel();

      await panel.exportBundle();

      expect(planningApi.exportBundle).toHaveBeenCalledExactlyOnceWith({ postes: [] });
      expect(messages.at(-1)).toBe('Téléchargement démarré.');
    });

    it('lowers the busy flag and reports the refusal when the server declines', async () => {
      planningApi.exportGlobalPdf.mockRejectedValue(new Error('Planning vide.'));
      const panel = createPanel();

      await panel.exportGlobalPdf();

      expect(exportBusyChanges.at(-1)).toBe(false);
      expect(messages.at(-1)).toContain('Planning vide.');
    });
  });

  /* -------------------------- The review table --------------------------- */

  describe('review table', () => {
    function destinataire(partiel: Record<string, unknown>): Record<string, unknown> {
      return {
        animateurId: 'a1',
        nomAffiche: 'Alice Martin',
        email: 'alice@example.org',
        premiereDiffusion: false,
        changements: ['samedi 11/07 : Cirque 14h-18h (nouveau)'],
        demandes: [],
        ajouts: 1,
        retraits: 0,
        deplacements: 0,
        mineur: false,
        reporte: false,
        confirmation: null,
        confirmeLe: null,
        ...partiel,
      };
    }

    async function panelWith(destinataires: Record<string, unknown>[]): Promise<PanelInternals> {
      planningApi.publicationPreview.mockResolvedValue({
        ...apercuPret,
        nombreConcernes: destinataires.length,
        destinataires,
      });
      const panel = createPanel();
      await vi.waitFor(() => expect(panel.rows()).toHaveLength(destinataires.length));
      return panel;
    }

    it('names the deferred people to the server, and nobody else', async () => {
      confirm.ask.mockResolvedValue(true);
      planningApi.publish.mockResolvedValue({
        envoyes: 1,
        sansEmail: [],
        echecs: [],
        differes: ['Bruno Petit'],
      });
      const panel = await panelWith([
        destinataire({}),
        destinataire({ animateurId: 'a2', nomAffiche: 'Bruno Petit' }),
      ]);

      panel.toggleExclusion('a2', false);
      expect(panel.isExcluded('a2')).toBe(true);
      expect(panel.notifiedCount()).toBe(1);

      await panel.publish();

      expect(planningApi.publish).toHaveBeenCalledExactlyOnceWith(['a2']);
      expect(messages.at(-1)).toContain('Bruno Petit');
    });

    /**
     * The last tick is the one that would publish to nobody: the button has to
     * be inert before the click, not refused by the server after it.
     */
    it('goes inert when every single person has been unticked', async () => {
      const panel = await panelWith([destinataire({})]);

      panel.toggleExclusion('a1', false);

      expect(panel.notifiedCount()).toBe(0);
      expect(panel.publishable()).toBe(false);
    });

    it('folds the minor changes away without excluding them', async () => {
      const panel = await panelWith([
        destinataire({}),
        destinataire({ animateurId: 'a2', mineur: true, ajouts: 0, deplacements: 1 }),
      ]);

      expect(panel.minorCount()).toBe(1);
      panel.toggleMinorFilter(true);

      expect(panel.rows()).toHaveLength(1);
      // Hiding is looking, not deciding: both people are still to be notified.
      expect(panel.notifiedCount()).toBe(2);
    });

    it('puts the biggest change first when asked to', async () => {
      const panel = await panelWith([
        destinataire({}),
        destinataire({ animateurId: 'a2', ajouts: 3, retraits: 1 }),
      ]);

      panel.chooseSort('ampleur');

      expect(panel.rows().map((ligne) => ligne.animateurId)).toEqual(['a2', 'a1']);
    });

    /**
     * An exclusion only means something about somebody the list still names:
     * a change undone between two reads must not leave them silently ticked
     * off for the next publication.
     */
    it('drops an exclusion once its person leaves the list', async () => {
      const panel = await panelWith([
        destinataire({}),
        destinataire({ animateurId: 'a2', nomAffiche: 'Bruno Petit' }),
      ]);
      panel.toggleExclusion('a2', false);

      planningApi.publicationPreview.mockResolvedValue({
        ...apercuPret,
        nombreConcernes: 1,
        destinataires: [destinataire({})],
      });
      await panel.reloadPreview();

      expect(panel.isExcluded('a2')).toBe(false);
      expect(panel.notifiedCount()).toBe(1);
    });

    it('downloads the review table without sending anything', async () => {
      planningApi.exportPublicationDiff.mockResolvedValue('Téléchargement démarré.');
      const panel = await panelWith([destinataire({})]);

      await panel.exportDiff();

      expect(planningApi.exportPublicationDiff).toHaveBeenCalledOnce();
      expect(planningApi.publish).not.toHaveBeenCalled();
      expect(messages.at(-1)).toBe('Téléchargement démarré.');
    });
  });
});
