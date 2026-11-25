import { Provider } from '@angular/core';
import { SessionStore } from '../session.store';

/** A session store already read, for the specs of screens that ask who the person is. */
export function provideSession(admin = true): Provider {
  return {
    provide: SessionStore,
    useValue: {
      isAdmin: () => admin,
      lectureSeule: () => false,
      known: () => true,
      statut: () => ({
        authentifie: true,
        nom: 'a@b.fr',
        roles: [admin ? 'admin' : 'organisateur'],
        compteId: 'moi',
      }),
      ready: () => Promise.resolve(),
      refresh: () => Promise.resolve(),
    },
  };
}
