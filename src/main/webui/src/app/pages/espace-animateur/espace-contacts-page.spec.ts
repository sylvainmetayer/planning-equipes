import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { ContactsAnimateur, ContactsEnregistres } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { EspaceContactsPage } from './espace-contacts-page';

describe('EspaceContactsPage', () => {
  function setup(
    contacts: () => Promise<ContactsAnimateur>,
    enregistrerContacts: () => Promise<ContactsEnregistres> = () =>
      Promise.resolve({ contacts: { urgenceNom: 'Camille' }, avertissements: [] }),
  ) {
    const service = fakeOf<EspaceAnimateurService>({ contacts, enregistrerContacts });
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
    expect(service.enregistrerContacts).toHaveBeenCalled();
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
});
