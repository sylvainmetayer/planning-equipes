// Publishing (retour utilisateur, #333). Tens of seconds used to pass with no
// sign at all, on the one action that writes to real people — and the natural
// reflex in front of a screen that says nothing is to click again. The
// documents moved to their own tab (documents-panel.spec.ts).

import { provideZonelessChangeDetection, Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PublicationPanel } from './publication-panel';
import { GelInvitation } from '../../core/gel-invitation';
import { PublicationSelection } from './publication-selection';

const gelOffer = vi.fn().mockResolvedValue(undefined);

type PanelInternals = {
  busy: Signal<boolean>;
  publishable: Signal<boolean>;
  recipientsInFlight: Signal<number>;
  publish: () => Promise<void>;
  publishSentence: Signal<string>;
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

function recipient(partiel: Record<string, unknown>): Record<string, unknown> {
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
    jours: ['2026-07-11'],
    ...partiel,
  };
}

describe('PublicationPanel', () => {
  const planningApi = {
    publicationPreview: vi.fn(),
    publish: vi.fn(),
  };
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
    envoisEnEchec: 0,
  };

  let fixture: ComponentFixture<PublicationPanel>;
  /** Everything the panel said to the page's output panel, in order. */
  let messages: string[];
  let published: number;

  beforeEach(() => {
    editingLocked.set(false);
    for (const stub of [planningApi.publicationPreview, planningApi.publish, confirm.ask]) {
      stub.mockReset();
    }
    planningApi.publicationPreview.mockResolvedValue({});
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanningApi, useValue: planningApi },
        PublicationSelection,
        { provide: ConfirmService, useValue: confirm },
        { provide: SolverJobService, useValue: { editingLocked: () => editingLocked() } },
        // The freeze invitation writes to localStorage: kept out of this file's storage.
        { provide: GelInvitation, useValue: { offer: gelOffer } },
      ],
    });
  });

  function createPanel(): PanelInternals {
    fixture = TestBed.createComponent(PublicationPanel);
    messages = [];
    published = 0;
    fixture.componentInstance.reported.subscribe((message) => messages.push(message));
    fixture.componentInstance.published.subscribe(() => published++);
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
    // A published plan is the second milestone at which freezing is offered.
    expect(gelOffer).toHaveBeenCalledWith('publication');
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

  /* -------------------------- The review table --------------------------- */

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

  describe('review table', () => {
    it('names the deferred people to the server, and nobody else', async () => {
      confirm.ask.mockResolvedValue(true);
      planningApi.publish.mockResolvedValue({
        envoyes: 1,
        sansEmail: [],
        echecs: [],
        differes: ['Bruno Petit'],
      });
      const panel = await panelWith([
        recipient({}),
        recipient({ animateurId: 'a2', nomAffiche: 'Bruno Petit' }),
      ]);

      panel.toggleExclusion('a2', false);
      expect(panel.isExcluded('a2')).toBe(true);
      expect(panel.notifiedCount()).toBe(1);

      await panel.publish();

      expect(planningApi.publish).toHaveBeenCalledExactlyOnceWith(['a2']);
      expect(messages.at(-1)).toContain('Bruno Petit');
      // The table under the panel is told to read the new states.
      expect(published).toBe(1);
    });

    it('says both effects of the button in one sentence, counting the people kept', async () => {
      const panel = await panelWith([
        recipient({}),
        recipient({ animateurId: 'a2', nomAffiche: 'Bruno Petit' }),
      ]);

      expect(panel.publishSentence()).toContain('2');
      expect(panel.publishSentence()).toContain('met à jour leur espace');
      panel.toggleExclusion('a2', false);
      expect(panel.publishSentence()).toContain('1');
    });

    /**
     * The last tick is the one that would publish to nobody: the button has to
     * be inert before the click, not refused by the server after it.
     */
    it('goes inert when every single person has been unticked', async () => {
      const panel = await panelWith([recipient({})]);

      panel.toggleExclusion('a1', false);

      expect(panel.notifiedCount()).toBe(0);
      expect(panel.publishable()).toBe(false);
    });

    it('folds the minor changes away without excluding them', async () => {
      const panel = await panelWith([
        recipient({}),
        recipient({ animateurId: 'a2', mineur: true, ajouts: 0, deplacements: 1 }),
      ]);

      expect(panel.minorCount()).toBe(1);
      panel.toggleMinorFilter(true);

      expect(panel.rows()).toHaveLength(1);
      // Hiding is looking, not deciding: both people are still to be notified.
      expect(panel.notifiedCount()).toBe(2);
    });

    it('puts the biggest change first when asked to', async () => {
      const panel = await panelWith([
        recipient({}),
        recipient({ animateurId: 'a2', ajouts: 3, retraits: 1 }),
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
        recipient({}),
        recipient({ animateurId: 'a2', nomAffiche: 'Bruno Petit' }),
      ]);
      panel.toggleExclusion('a2', false);

      planningApi.publicationPreview.mockResolvedValue({
        ...apercuPret,
        nombreConcernes: 1,
        destinataires: [recipient({})],
      });
      await panel.reloadPreview();

      expect(panel.isExcluded('a2')).toBe(false);
      expect(panel.notifiedCount()).toBe(1);
    });
  });
});
