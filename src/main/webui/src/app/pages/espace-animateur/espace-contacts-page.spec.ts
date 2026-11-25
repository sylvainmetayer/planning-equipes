import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { ContactsAnimateur, ContactsEnregistres } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { EspaceContactsPage } from './espace-contacts-page';

describe('EspaceContactsPage', () => {
  function setup(
    contacts: () => Promise<ContactsAnimateur>,
    saveContacts: () => Promise<ContactsEnregistres> = () =>
      Promise.resolve({ contacts: { urgenceNom: 'Camille' }, avertissements: [] }),
  ) {
    const service = fakeOf<EspaceAnimateurService>({ contacts, saveContacts });
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideFake(EspaceAnimateurService, service)],
    });
    return service;
  }

  async function render() {
    const fixture = TestBed.createComponent(EspaceContactsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  it('loads my own contacts into the form', async () => {
    setup(() => Promise.resolve({ urgenceNom: 'Camille' }));
    const fixture = await render();
    expect(fixture.nativeElement.querySelector('input[name="urgenceNom"]').value).toBe('Camille');
  });

  it('saves them, and says the warning for a minor without a representative', async () => {
    const service = setup(
      () => Promise.resolve({}),
      () =>
        Promise.resolve({
          contacts: { urgenceNom: 'Camille' },
          avertissements: [{ type: 'MINEUR_SANS_REPRESENTANT', message: 'Représentant manquant' }],
        }),
    );
    const fixture = await render();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(service.saveContacts).toHaveBeenCalled();
    expect(text(fixture)).toContain('Représentant manquant');
    expect(text(fixture)).toContain('Vos contacts sont enregistrés');
  });

  it('shows a refusal instead of the confirmation', async () => {
    setup(
      () => Promise.resolve({}),
      () => Promise.reject(new Error('Numéro de téléphone invalide')),
    );
    const fixture = await render();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Numéro de téléphone invalide');
    expect(text(fixture)).not.toContain('Vos contacts sont enregistrés');
  });

  it('sends back the stamp it read, and the newer one after a refusal so a second save overwrites', async () => {
    let calls = 0;
    const service = setup(
      () => Promise.resolve({ urgenceNom: 'Camille', modifieLe: '2026-11-25T10:00:00Z' }),
      () => {
        calls++;
        return calls === 1
          ? Promise.reject(
              new ApiError(
                409,
                'conflict',
                'Ces contacts ont été modifiés par quelqu’un d’autre',
                CODE_MODIFICATION_CONCURRENTE,
                '2026-11-25T11:00:00Z',
              ),
            )
          : Promise.resolve({ contacts: { urgenceNom: 'Camille' }, avertissements: [] });
      },
    );
    const fixture = await render();
    const form = fixture.nativeElement.querySelector('form');
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text(fixture)).toContain('modifiés par quelqu’un d’autre');
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(service.saveContacts).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ modifieLe: '2026-11-25T10:00:00Z' }),
    );
    expect(service.saveContacts).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ modifieLe: '2026-11-25T11:00:00Z' }),
    );
  });
});
