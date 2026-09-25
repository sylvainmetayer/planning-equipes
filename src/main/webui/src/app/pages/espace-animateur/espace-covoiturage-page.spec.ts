// The Covoiturage tab of the espace: open for input while the collection is,
// read-only otherwise, and saying where the request stands — the reason of a
// request set aside included, since that is what the animateur acts on.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { CarpoolEspaceView } from '../../core/models';
import { EspaceCovoituragePage } from './espace-covoiturage-page';

function view(overrides: Partial<CarpoolEspaceView> = {}): CarpoolEspaceView {
  return {
    collectionOpen: true,
    collectionStart: null,
    collectionEnd: null,
    colleagues: [
      { id: 'A2', nomComplet: 'Bob Durand' },
      { id: 'A3', nomComplet: 'Chloé Petit' },
    ],
    teammateIds: [],
    status: null,
    reason: null,
    decidedAt: null,
    ...overrides,
  };
}

describe('EspaceCovoituragePage', () => {
  let carpool: ReturnType<typeof signal<CarpoolEspaceView | null>>;
  let requestCarpool: ReturnType<typeof vi.fn>;

  async function render(
    initial: CarpoolEspaceView,
  ): Promise<ComponentFixture<EspaceCovoituragePage>> {
    carpool = signal<CarpoolEspaceView | null>(null);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: EspaceAnimateurService,
          useValue: {
            carpool,
            loadCarpool: vi.fn(async () => carpool.set(initial)),
            requestCarpool,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(EspaceCovoituragePage);
    fixture.detectChanges();
    await settle(fixture);
    return fixture;
  }

  /** The load is a plain promise, not a task the zoneless fixture waits for. */
  async function settle(fixture: ComponentFixture<EspaceCovoituragePage>): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  function text(fixture: ComponentFixture<EspaceCovoituragePage>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function button(
    fixture: ComponentFixture<EspaceCovoituragePage>,
    label: string,
  ): HTMLButtonElement | undefined {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
      (candidate) => candidate.textContent?.includes(label),
    );
  }

  beforeEach(() => {
    requestCarpool = vi.fn(async () =>
      carpool.set(view({ teammateIds: ['A2'], status: 'EN_ATTENTE' })),
    );
  });

  it('picks a teammate by name and sends the request on its own', async () => {
    const fixture = await render(view());
    expect(button(fixture, 'Envoyer ma demande')?.disabled).toBe(true);

    const input = (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
    input.value = 'bob';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    button(fixture, 'Bob Durand')!.click();
    await settle(fixture);
    button(fixture, 'Envoyer ma demande')!.click();
    await settle(fixture);

    expect(requestCarpool).toHaveBeenCalledWith({ teammateIds: ['A2'] });
    expect(text(fixture)).toContain("En attente de l'organisation — avec Bob Durand.");
  });

  it('shows a request set aside with the reason the organisation gave', async () => {
    const fixture = await render(
      view({ teammateIds: ['A2'], status: 'ECARTEE', reason: 'Bob ne vient que le samedi.' }),
    );

    expect(text(fixture)).toContain('a été écartée');
    expect(text(fixture)).toContain('Bob ne vient que le samedi.');
    expect(text(fixture)).toContain('nouvelle demande');
  });

  it('shows a validated car read-only, with no form', async () => {
    const fixture = await render(view({ teammateIds: ['A2'], status: 'VALIDEE' }));

    expect(text(fixture)).toContain("validée par l'organisation");
    expect(text(fixture)).toContain('Bob Durand');
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeNull();
    expect(button(fixture, 'Envoyer ma demande')).toBeUndefined();
  });

  it('shows a cancelled car with its reason and a blank picker while the collection is open', async () => {
    const fixture = await render(
      view({ teammateIds: ['A2'], status: 'ANNULEE', reason: 'La voiture est en panne.' }),
    );

    expect(text(fixture)).toContain(
      "L'organisation a annulé cette arrivée groupée avec Bob Durand.",
    );
    expect(text(fixture)).toContain('La voiture est en panne.');
    expect(text(fixture)).toContain('nouvelle demande');
    // The picker is open, and starts from nobody: nothing chosen, nothing to send.
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).not.toBeNull();
    expect(button(fixture, 'Envoyer ma demande')?.disabled).toBe(true);
  });

  it('sends to the organisation a car cancelled once the collection is closed', async () => {
    const fixture = await render(
      view({ collectionOpen: false, teammateIds: ['A2'], status: 'ANNULEE', reason: null }),
    );

    expect(text(fixture)).toContain("L'organisation a annulé cette arrivée groupée");
    expect(text(fixture)).toContain("adressez-vous à l'organisation");
    expect(text(fixture)).not.toContain('nouvelle demande ci-dessous');
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeNull();
  });

  it('is read-only while the collection is closed', async () => {
    const fixture = await render(
      view({ collectionOpen: false, teammateIds: ['A3'], status: 'EN_ATTENTE' }),
    );

    expect(text(fixture)).toContain('Les demandes sont fermées');
    expect(text(fixture)).toContain('avec Chloé Petit');
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeNull();
  });
});
