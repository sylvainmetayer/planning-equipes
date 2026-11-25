import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RhApi } from '../../core/api/rh-api';
import { ContactAnimateurView, ContactsEnregistres } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ContactsRh } from './contacts-rh';

const LIGNES: ContactAnimateurView[] = [
  {
    animateurId: 'A1',
    nom: 'Ada Test',
    mineur: false,
    contacts: { urgenceNom: 'Camille', urgenceTelephone: '+33000000001' },
  },
  { animateurId: 'B1', nom: 'Bob Test', mineur: true, contacts: {} },
];

describe('ContactsRh', () => {
  function setup(
    saveContacts: () => Promise<ContactsEnregistres> = () =>
      Promise.resolve({ contacts: {}, avertissements: [] }),
  ) {
    const api = fakeOf<RhApi>({ contacts: () => Promise.resolve(LIGNES), saveContacts });
    const notifications = fakeOf<NotificationService>({ notify: vi.fn() });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(RhApi, api),
        provideFake(NotificationService, notifications),
      ],
    });
    return { api, notifications };
  }

  async function render() {
    const fixture = TestBed.createComponent(ContactsRh);
    fixture.componentRef.setInput('editionId', 'E1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  function saveButton(fixture: { nativeElement: HTMLElement }): HTMLButtonElement {
    const boutons = Array.from(
      fixture.nativeElement.querySelectorAll<HTMLButtonElement>('tbody button'),
    );
    return boutons.find((b) => (b.textContent ?? '').includes('Enregistrer')) as HTMLButtonElement;
  }

  it('shows each contact with a tel: link and flags a minor with no representative', async () => {
    setup();
    const fixture = await render();
    expect(text(fixture)).toContain('Camille');
    expect(fixture.nativeElement.querySelector('a[href="tel:+33000000001"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.conformite-alerte').length).toBe(1);
    expect(text(fixture)).toContain('Non renseigné');
  });

  it('edits a line, saves it and passes the warnings on to the notifications', async () => {
    const { api, notifications } = setup(() =>
      Promise.resolve({
        contacts: {},
        avertissements: [
          { type: 'MINEUR_SANS_REPRESENTANT', message: "L'animateur B1 est mineur" },
        ],
      }),
    );
    const fixture = await render();
    const boutons = fixture.nativeElement.querySelectorAll('tbody button');
    boutons[1].click();
    fixture.detectChanges();
    const champ = fixture.nativeElement.querySelector('input[name="representantNom"]');
    champ.value = 'Dominique';
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    saveButton(fixture).click();
    await fixture.whenStable();

    expect(api.saveContacts).toHaveBeenCalledWith(
      'E1',
      'B1',
      expect.objectContaining({ representantNom: 'Dominique' }),
    );
    const notification = notifications.notify.mock.calls[0][0];
    expect(notification.variant).toBe('warning');
    expect(notification.messageJournal).toBe('');
  });

  it('tells a refusal and keeps the form open', async () => {
    const { notifications } = setup(() => Promise.reject(new Error('Numéro invalide')));
    const fixture = await render();
    fixture.nativeElement.querySelectorAll('tbody button')[0].click();
    fixture.detectChanges();
    saveButton(fixture).click();
    await fixture.whenStable();
    expect(notifications.notify.mock.calls[0][0].variant).toBe('error');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[name="urgenceNom"]')).not.toBeNull();
  });
});
