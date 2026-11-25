import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { ResponsableApi } from '../../core/api/responsable-api';
import { ResponsableView, StandResponsable } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ResponsablePage } from './responsable-page';

const STAND: StandResponsable = {
  standId: 'S1',
  standNom: 'Buvette',
  emplacementNom: 'Hall A',
  nominatif: true,
  vacations: [
    {
      debut: '2026-08-01T10:00:00',
      fin: '2026-08-01T12:00:00',
      pourvus: 1,
      vides: 1,
      personnes: [{ prenom: 'Ada', nom: 'Lovelace' }],
    },
  ],
};

describe('ResponsablePage', () => {
  function setup(
    stands: () => Promise<ResponsableView>,
    logout?: () => Promise<{ urlDeconnexion: string | null }>,
  ) {
    const api = fakeOf<ResponsableApi>({ stands, affichette: () => Promise.resolve('ok') });
    const adminApi = fakeOf<AdminApi>({
      logout: logout ?? (() => Promise.resolve({ urlDeconnexion: null })),
    });
    const notifications = fakeOf<NotificationService>({ notify: vi.fn() });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(ResponsableApi, api),
        provideFake(AdminApi, adminApi),
        provideFake(NotificationService, notifications),
      ],
    });
    return { api, adminApi, notifications };
  }

  async function render() {
    const fixture = TestBed.createComponent(ResponsablePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  it('shows the shifts of the stand, its seats to fill and the names when they are shown', async () => {
    setup(() =>
      Promise.resolve({
        editionNom: 'Édition 2026',
        publieLe: '2026-07-01T08:00:00',
        stands: [STAND],
      }),
    );
    const fixture = await render();
    const content = text(fixture);
    expect(content).toContain('Édition 2026');
    expect(content).toContain('Buvette');
    expect(content).toContain('Hall A');
    expect(content).toContain('10:00 – 12:00');
    expect(content).toContain('1 à pourvoir');
    expect(content).toContain('Ada Lovelace');
    expect(content).toContain('Planning publié le');
  });

  it('hides the names when the stand is not nominative and says when they come', async () => {
    setup(() =>
      Promise.resolve({
        editionNom: 'Édition 2026',
        publieLe: '2026-07-01T08:00:00',
        stands: [{ ...STAND, nominatif: false, nomsAttendus: '2026-07-20T09:00:00' }],
      }),
    );
    const content = text(await render());
    expect(content).not.toContain('Ada Lovelace');
    expect(content).toContain("Les noms s'afficheront à partir du");
  });

  it('says nothing is published yet, and that a stand has no shift', async () => {
    setup(() =>
      Promise.resolve({ editionNom: null, publieLe: null, stands: [{ ...STAND, vacations: [] }] }),
    );
    const content = text(await render());
    expect(content).toContain('Mon stand');
    expect(content).toContain('pas encore publié');
    expect(content).toContain('Aucune vacation publiée');
  });

  it('tells a responsable with no stand that there is nothing to show', async () => {
    setup(() => Promise.resolve({ stands: [] }));
    const content = text(await render());
    expect(content).toContain('Aucun stand à afficher');
    expect(content).not.toContain('Planning publié');
  });

  it('shows the error of a failed read instead of the loading message', async () => {
    setup(() => Promise.reject(new Error('Indisponible')));
    const fixture = await render();
    expect(fixture.componentInstance['erreur']()).not.toBe('');
    expect(text(fixture)).not.toContain('Chargement');
  });

  it('prints the sheet of the stand asked for', async () => {
    const { api } = setup(() => Promise.resolve({ stands: [STAND] }));
    const fixture = await render();
    await fixture.componentInstance['imprimer'](STAND);
    expect(api.affichette).toHaveBeenCalledWith('S1');
  });

  it('notifies the error when the sheet cannot be printed', async () => {
    const { api, notifications } = setup(() => Promise.resolve({ stands: [STAND] }));
    api.affichette.mockRejectedValue(new Error('PDF en échec'));
    const fixture = await render();
    await fixture.componentInstance['imprimer'](STAND);
    expect(notifications.notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'error' }),
    );
  });

  describe('logging out', () => {
    it("leaves for the provider's route when it names one", async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign });
      setup(
        () => Promise.resolve({ stands: [] }),
        () => Promise.resolve({ urlDeconnexion: 'https://idp.example/logout' }),
      );
      const fixture = await render();
      await fixture.componentInstance['logout']();
      expect(assign).toHaveBeenCalledExactlyOnceWith('https://idp.example/logout');
      vi.restoreAllMocks();
    });

    it('leaves for /login when the provider names none', async () => {
      const assign = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign });
      setup(() => Promise.resolve({ stands: [] }));
      const fixture = await render();
      await fixture.componentInstance['logout']();
      expect(assign).toHaveBeenCalledExactlyOnceWith('/login');
      vi.restoreAllMocks();
    });
  });
});
