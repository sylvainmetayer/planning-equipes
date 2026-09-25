import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { AffichageMuralView } from '../../core/models';
import { MuralPage } from './mural-page';

const JOUR = '2026-07-08';

const VIEW: AffichageMuralView = {
  edition: 'Année 2026',
  libelle: 'TV PC sécurité',
  jour: JOUR,
  now: `${JOUR}T10:00:00`,
  nextDay: null,
  stands: [
    {
      standId: 'jeux',
      standNom: 'Jeux géants',
      emplacementId: 'nord',
      emplacementNom: 'Zone nord',
      vacations: [
        {
          start: `${JOUR}T09:00:00`,
          end: `${JOUR}T13:00:00`,
          noms: ['Camille D.'],
          emptySeats: 1,
        },
        { start: `${JOUR}T14:00:00`, end: `${JOUR}T18:00:00`, noms: ['Léo M.'], emptySeats: 0 },
      ],
    },
    {
      standId: 'bar',
      standNom: 'Buvette',
      emplacementId: 'nord',
      emplacementNom: 'Zone nord',
      vacations: [{ start: `${JOUR}T15:00:00`, end: `${JOUR}T19:00:00`, noms: [], emptySeats: 2 }],
    },
  ],
  alerts: [
    {
      type: 'EMPTY_SEATS',
      standNom: 'Jeux géants',
      start: `${JOUR}T09:00:00`,
      end: `${JOUR}T13:00:00`,
      count: 1,
      nom: null,
    },
  ],
  consigne: null,
};

describe('MuralPage', () => {
  let fixture: ComponentFixture<MuralPage>;
  let view: ReturnType<typeof vi.fn>;

  async function rendre(query: Record<string, string> = {}): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AffichageMuralApi, useValue: { view } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ jeton: 'abc' }),
              queryParamMap: convertToParamMap(query),
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(MuralPage);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'performance'],
    });
    view = vi.fn(async () => VIEW);
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
  });

  it('shows the shift under way, the next one, the empty seats and the closed stands', async () => {
    await rendre();

    expect(view).toHaveBeenCalledWith('abc');
    expect(text()).toContain('Zone nord');
    expect(text()).toContain('09:00–13:00');
    expect(text()).toContain('Camille D.');
    expect(text()).toContain('Place libre');
    expect(text()).toContain('Ensuite 14:00–18:00');
    expect(text()).toContain('Jeux géants : 1 × place libre 09:00–13:00');
    expect(text()).toContain('Planning de travail');
  });

  /** jsdom's 1024 × 768 holds one large tile: the second stand waits for the next page. */
  it('turns the pages every fifteen seconds, saying which one is on screen', async () => {
    await rendre();
    expect(text()).toContain('Page 1/2');
    expect(text()).not.toContain('Buvette');

    await vi.advanceTimersByTimeAsync(15_000);
    fixture.detectChanges();

    expect(text()).toContain('Page 2/2');
    expect(text()).toContain('Buvette');
    expect(text()).toContain('Fermé — réouverture à 15:00');
  });

  it('keeps the last state when a read fails, and says it is offline', async () => {
    await rendre();
    view.mockRejectedValue(new HttpErrorResponse({ status: 0 }));

    await vi.advanceTimersByTimeAsync(60_000);
    fixture.detectChanges();

    expect(text()).toContain('Camille D.');
    expect(text()).toContain('Hors ligne depuis');
  });

  it('concludes the link is dead only after three 404s in a row, then asks every five minutes', async () => {
    await rendre();
    view.mockRejectedValue(new HttpErrorResponse({ status: 404 }));

    await vi.advanceTimersByTimeAsync(60_000);
    fixture.detectChanges();
    // One 404 may be a restart or a proxy hiccup: the last state stays.
    expect(text()).toContain('Jeux géants : 1 × place libre');
    expect(text()).not.toContain('Lien inconnu ou révoqué');

    await vi.advanceTimersByTimeAsync(120_000);
    fixture.detectChanges();
    expect(text()).toContain('Lien inconnu ou révoqué');
    expect(view).toHaveBeenCalledTimes(4);

    await vi.advanceTimersByTimeAsync(240_000);
    expect(view).toHaveBeenCalledTimes(4);
    view.mockResolvedValue(VIEW);
    await vi.advanceTimersByTimeAsync(60_000);
    fixture.detectChanges();
    expect(view).toHaveBeenCalledTimes(5);
    expect(text()).toContain('Jeux géants : 1 × place libre');
  });

  it('gives up on a read that hangs, and says the screen is offline', async () => {
    await rendre();
    view.mockImplementation(() => new Promise(() => undefined));

    await vi.advanceTimersByTimeAsync(60_000 + 20_000);
    fixture.detectChanges();

    expect(text()).toContain('Jeux géants : 1 × place libre');
    expect(text()).toContain('Hors ligne depuis');
  });

  it('drops an answer that comes back after its read was given up', async () => {
    await rendre();
    let lateAnswer: (value: AffichageMuralView) => void = () => undefined;
    view.mockImplementationOnce(
      () => new Promise<AffichageMuralView>((resolve) => (lateAnswer = resolve)),
    );
    view.mockResolvedValueOnce({ ...VIEW, libelle: 'Récente' });

    await vi.advanceTimersByTimeAsync(60_000 + 25_000);
    lateAnswer({ ...VIEW, libelle: 'Ancienne' });
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    expect(text()).not.toContain('Ancienne');
    expect(text()).toContain('Hors ligne depuis');

    await vi.advanceTimersByTimeAsync(35_000);
    fixture.detectChanges();
    expect(text()).toContain('Récente');
    expect(text()).not.toContain('Hors ligne depuis');
  });

  it('writes « minuit » for a consigne running until midnight', async () => {
    view.mockResolvedValue({
      ...VIEW,
      consigne: { closedFrom: '20:00:00', closedUntil: null, motif: 'Arrêté' },
    });
    await rendre();

    expect(text()).toContain('tous les stands fermés 20:00–minuit');
  });

  it('shows both shifts under way when they overlap on one stand', async () => {
    view.mockResolvedValue({
      ...VIEW,
      now: `${JOUR}T13:00:00`,
      stands: [
        {
          ...VIEW.stands[0],
          vacations: [
            {
              start: `${JOUR}T10:00:00`,
              end: `${JOUR}T14:00:00`,
              noms: ['Camille D.'],
              emptySeats: 0,
            },
            { start: `${JOUR}T12:00:00`, end: `${JOUR}T16:00:00`, noms: ['Léo M.'], emptySeats: 1 },
          ],
        },
      ],
    });
    await rendre();

    expect(text()).toContain('10:00–14:00');
    expect(text()).toContain('Camille D.');
    expect(text()).toContain('12:00–16:00');
    expect(text()).toContain('Léo M.');
    expect(text()).toContain('Place libre');
  });

  it('lays out every shift of the day for print, with neither clock nor band', async () => {
    await rendre({ impression: '1' });

    expect(text()).toContain('09:00–13:00');
    expect(text()).toContain('14:00–18:00');
    expect(text()).toContain('15:00–19:00');
    expect((fixture.nativeElement as HTMLElement).querySelector('.mural-horloge')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.mural-bandeau')).toBeNull();
  });
});
