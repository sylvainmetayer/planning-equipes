// An organiser's first day: a local account invited by the admin API, its
// password chosen through the mailed link, its code read in Mailpit — then the
// application must open on its edition, without a loop back to /login, and
// keep the instance's settings out of its reach.

import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { contexteAdmin, dernierCodeMailpit, EDITION_REFERENCE, nombreDeMails } from './support';

const MAILPIT_URL = process.env['E2E_MAILPIT_URL'] ?? 'http://localhost:8025';

test.describe('organisateur avec compte local', () => {
  test("arrive sur l'appli, voit son édition, pas les réglages d'instance", async ({
    page,
    playwright,
    baseURL,
  }) => {
    const admin = await contexteAdmin(playwright, baseURL as string);
    const email = `organisateur-${randomUUID()}@example.org`;
    // Generated, never typed: a literal here would be a credential in the repository.
    const motDePasse = `${randomUUID()}-${randomUUID()}`;
    let compteId: string | null = null;
    try {
      const cree = await admin.post('/api/comptes', { data: { email, nom: 'Organisateur E2E' } });
      expect(cree.ok(), await cree.text()).toBe(true);
      compteId = ((await cree.json()) as { id: string }).id;
      const droit = await admin.post(`/api/comptes/${compteId}/habilitations`, {
        data: { role: 'ORGANISATEUR', editionId: EDITION_REFERENCE },
      });
      expect(droit.ok(), await droit.text()).toBe(true);
      // Refused when PUBLIC_URL points at localhost: the stack gives it a name of its own.
      const invitation = await admin.post(`/api/comptes/${compteId}/invitation`);
      expect(invitation.ok(), await invitation.text()).toBe(true);

      // The link the invited person receives: the token rides in its fragment.
      let jeton: string | undefined;
      await expect
        .poll(
          async () => {
            const recherche = await page.request.get(
              `${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`,
            );
            const trouves = ((await recherche.json()) as { messages: { ID: string }[] }).messages;
            for (const message of trouves) {
              const detail = await page.request.get(`${MAILPIT_URL}/api/v1/message/${message.ID}`);
              const texte = ((await detail.json()) as { Text: string }).Text;
              jeton = /\/mot-de-passe#(\S+)/.exec(texte)?.[1];
              if (jeton) return true;
            }
            return false;
          },
          { message: "le lien d'invitation doit arriver dans Mailpit" },
        )
        .toBe(true);
      const choix = await page.request.post('/api/auth/mot-de-passe', {
        data: { token: jeton, password: motDePasse },
      });
      expect(choix.status()).toBe(204);

      // Sign in through the screen: password, then the code mailed to the address.
      await page.goto('/login');
      await page.getByLabel('Adresse e-mail').fill(email);
      await page.getByLabel('Mot de passe').fill(motDePasse);
      // The invitation already sits in Mailpit: only a mail after the click carries the code.
      const mailsAvant = await nombreDeMails(page.request, email);
      await page.getByRole('button', { name: 'Se connecter' }).click();
      const code = await dernierCodeMailpit(page.request, email, mailsAvant);
      await page.getByLabel('Code reçu par e-mail').fill(code);
      await page.getByRole('button', { name: 'Se connecter' }).click();

      const navigation = page.getByRole('navigation', { name: 'Navigation principale' });
      await expect(navigation).toBeVisible();
      await expect(page).not.toHaveURL(/\/login/);
      // A reload keeps the session: no bounce back to the login page.
      await page.reload();
      await expect(navigation).toBeVisible();
      await expect(page).not.toHaveURL(/\/login/);

      const statut = (await (await page.request.get('/api/auth/me')).json()) as {
        authentifie: boolean;
        roles: string[];
      };
      expect(statut.authentifie).toBe(true);
      expect(statut.roles).toContain('organisateur');
      expect(statut.roles).not.toContain('admin');

      // Its edition, the one its right names.
      const editions = await page.request.get('/api/editions');
      expect(editions.ok()).toBe(true);
      expect(((await editions.json()) as { id: string }[]).map((e) => e.id)).toContain(
        EDITION_REFERENCE,
      );

      // The instance tabs are not offered, and landing on one by its address
      // brings the organiser back to the edition's own.
      await page.goto('/parametres?onglet=comptes');
      const onglets = page.getByRole('radiogroup', { name: 'Onglet des paramètres' });
      await expect(onglets.getByRole('radio', { name: 'Édition', exact: true })).toBeChecked();
      await expect(onglets.getByRole('radio', { name: 'Comptes', exact: true })).toHaveCount(0);
      await expect(onglets.getByRole('radio', { name: 'Instance', exact: true })).toHaveCount(0);
      await expect(page).not.toHaveURL(/onglet=comptes/);

      // And the API refuses an instance route, whatever the screen shows.
      for (const route of ['/api/comptes', '/api/backups']) {
        expect((await page.request.get(route)).status(), route).toBe(403);
      }
    } finally {
      if (compteId) {
        await admin.post(`/api/comptes/${compteId}/desactivation`);
      }
      await admin.dispose();
    }
  });
});
