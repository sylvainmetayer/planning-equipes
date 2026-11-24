-- Connexion par un fournisseur d'identité OIDC.
--
-- Le sujet (`sub`) est l'identité stable que le fournisseur donne à une
-- personne : l'adresse peut changer, pas lui. Un compte créé à la main par
-- l'adresse reçoit son sujet à la première connexion (adresse vérifiée par le
-- fournisseur) ; ensuite seul le sujet le désigne, de sorte qu'une autre
-- personne obtenant la même adresse chez le fournisseur n'en hérite pas.
-- Ce n'est pas un secret, mais c'est un identifiant de personne : la table
-- reste hors de l'export SQL, comme le reste de `compte`.
ALTER TABLE compte ADD COLUMN oidc_sujet VARCHAR(255);

CREATE UNIQUE INDEX compte_oidc_sujet_unique ON compte (oidc_sujet) WHERE oidc_sujet IS NOT NULL;

-- Le journal des connexions admin nomme deux événements de plus : l'ouverture
-- du compte de secours au démarrage (derrière un fournisseur OIDC, où il est
-- fermé par défaut) et chaque connexion qui passe par lui.
ALTER TABLE journal_connexion DROP CONSTRAINT IF EXISTS journal_connexion_evenement_check;
ALTER TABLE journal_connexion ADD CONSTRAINT journal_connexion_evenement_check
    CHECK (evenement IN ('CONNEXION', 'ECHEC', 'VERROUILLAGE', 'SECOURS_OUVERT', 'CONNEXION_SECOURS'));
