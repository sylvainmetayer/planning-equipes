// Shared plumbing of the perimeter suite: an authenticated admin API context,
// and the seeding of a tiny two-seat planning through the admin API only —
// exactly what a browser could do, no backdoor into the database.

import { APIRequestContext, expect, Playwright } from '@playwright/test';

export const MOT_DE_PASSE_ADMIN = process.env['E2E_ADMIN_PASSWORD'] ?? 'admin';

/** Ids of everything the suite seeds, so reseeding stays idempotent. */
export const SEED = {
  demandeur: 'E2E-A',
  cible: 'E2E-B',
  standDemandeur: 'E2E-S1',
  standCible: 'E2E-S2',
  creneauId: 987001
} as const;

/**
 * Opens an API context holding a fresh admin session cookie (form login).
 * Callers must `dispose()` it.
 */
export async function contexteAdmin(
  playwright: Playwright,
  baseURL: string
): Promise<APIRequestContext> {
  const request = await playwright.request.newContext({ baseURL });
  const connexion = await request.post('/j_security_check', {
    form: { j_username: 'admin', j_password: MOT_DE_PASSE_ADMIN },
    maxRedirects: 0
  });
  expect(connexion.status(), 'form login should answer the landing redirect').toBe(302);
  return request;
}

/**
 * Seeds (idempotently) a two-seat persisted planning on the same créneau:
 * Alice on stand E2E-S1, Bruno on stand E2E-S2 — the smallest dataset on
 * which an échange croisé exists. Replayed through `/api/database/import`,
 * whose statements are restricted to the business tables server-side.
 */
export async function seedPlanning(admin: APIRequestContext): Promise<void> {
  const script = [
    // Clean previous runs, children first.
    `delete from demande_echange where demandeur_id like 'E2E-%' or cible_id like 'E2E-%';`,
    `delete from verrouillage_planning where animateur_id like 'E2E-%';`,
    `delete from poste_affectation where id like 'E2E-%';`,
    `delete from creneau where id = ${SEED.creneauId};`,
    `delete from animateur where id like 'E2E-%';`,
    `delete from stand where id like 'E2E-%';`,
    // The dataset itself. jeton_acces is deliberately omitted: the database
    // generates it, and the suite reads it back through the admin API.
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standDemandeur}', 'Stand E2E un', 1, 1, false);`,
    `insert into stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) values ('DEFAUT', '${SEED.standCible}', 'Stand E2E deux', 1, 1, false);`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.demandeur}', 'Alice', 'E2E', '1990-01-01', false);`,
    `insert into animateur (edition_id, id, prenom, nom, date_naissance, manager) values ('DEFAUT', '${SEED.cible}', 'Bruno', 'E2E', '1992-02-02', false);`,
    `insert into creneau (edition_id, id, date_creneau, heure_debut, heure_fin) values ('DEFAUT', ${SEED.creneauId}, '2026-07-10', '10:00', '12:00');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P1', '${SEED.standDemandeur}', ${SEED.creneauId}, '${SEED.demandeur}');`,
    `insert into poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) values ('DEFAUT', 'E2E-P2', '${SEED.standCible}', ${SEED.creneauId}, '${SEED.cible}');`
  ].join('\n');

  const importReponse = await admin.post('/api/database/import', {
    headers: { 'Content-Type': 'text/plain' },
    data: script
  });
  expect(importReponse.ok(), await importReponse.text()).toBe(true);
}

/** The database-generated espace token of one seeded animateur, via the admin API. */
export async function jetonDe(admin: APIRequestContext, animateurId: string): Promise<string> {
  const reponse = await admin.get('/api/animateurs');
  expect(reponse.ok()).toBe(true);
  const animateurs = (await reponse.json()) as { id: string; jetonAcces?: string }[];
  const jeton = animateurs.find((animateur) => animateur.id === animateurId)?.jetonAcces;
  expect(jeton, `animateur ${animateurId} must exist with a token`).toBeTruthy();
  return jeton as string;
}
