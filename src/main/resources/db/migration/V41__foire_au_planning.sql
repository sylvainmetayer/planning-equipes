-- Foire au planning (issue #165) : un animateur propose un échange de créneau
-- depuis son espace en libre-service, l'admin valide ou refuse explicitement.
--
-- Trois briques de schéma :
--   1. l'animateur gagne un email de contact (notification du résultat) et un
--      jeton d'accès opaque — le lien « espace animateur » imprimé sur son
--      planning PDF, seule clé d'entrée de cet espace (pas de compte, voir
--      issue #63) ;
--   2. le verrouillage gagne un type ANIMATEUR_CRENEAU (animateur + créneau) :
--      le « pin » posé à la validation d'un échange fige ce que chacun des
--      deux animateurs tient sur le créneau échangé, sans geler tout leur
--      planning comme le ferait un verrouillage ANIMATEUR ;
--   3. la table demande_echange porte les propositions et leur cycle de vie
--      (PROPOSEE → ACCEPTEE / REFUSEE, ou ANNULEE par le demandeur).

/* --------- 1. Animateur : email + jeton d'accès --------- */

ALTER TABLE animateur ADD COLUMN email VARCHAR(255);

-- DEFAULT côté base : tout INSERT qui omet la colonne (CRUD, import YAML,
-- upsert de persistance du planning) reçoit un jeton, et le backfill des
-- animateurs existants est immédiat — le lien PDF est imprimable sans repasser
-- par une édition de la fiche. Le jeton ne change ensuite que par l'action
-- explicite « régénérer ».
ALTER TABLE animateur ADD COLUMN jeton_acces VARCHAR(64) NOT NULL DEFAULT gen_random_uuid()::text;

-- Unique GLOBALEMENT (pas par édition) : le jeton est l'identifiant d'entrée de
-- l'espace animateur, il doit résoudre à lui seul le couple (édition, animateur).
CREATE UNIQUE INDEX idx_animateur_jeton_acces ON animateur (jeton_acces);

/* --------- 2. Verrouillage ANIMATEUR_CRENEAU --------- */

ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_cible_coherente;
ALTER TABLE verrouillage_planning ADD CONSTRAINT verrouillage_planning_cible_coherente CHECK (
    (type = 'ANIMATEUR' AND animateur_id IS NOT NULL AND stand_id IS NULL
        AND creneau_id IS NULL AND jour IS NULL)
    OR (type = 'STAND' AND stand_id IS NOT NULL AND animateur_id IS NULL
        AND creneau_id IS NULL AND jour IS NULL)
    OR (type = 'CRENEAU' AND creneau_id IS NOT NULL AND animateur_id IS NULL
        AND stand_id IS NULL AND jour IS NULL)
    OR (type = 'JOUR' AND jour IS NOT NULL AND animateur_id IS NULL
        AND stand_id IS NULL AND creneau_id IS NULL)
    OR (type = 'ANIMATEUR_CRENEAU' AND animateur_id IS NOT NULL AND creneau_id IS NOT NULL
        AND stand_id IS NULL AND jour IS NULL)
);

/* --------- 3. Demandes d'échange --------- */

-- Une demande référence le poste par son couple (créneau, stand) plutôt que par
-- l'id de poste_affectation : les ids de poste sont renumérotés à chaque build
-- (voir PlanningPersistenceService.chargerAnimateursParStandCreneau), le couple
-- créneau × stand est ce qui survit à une régénération.
CREATE TABLE demande_echange (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    -- Groupe de créneaux du planning au moment de la demande, dénormalisé comme
    -- plan_snapshot.groupe_creneau_id : une demande doit rester lisible même si
    -- le groupe est supprimé ensuite.
    groupe_creneau_id VARCHAR(64),
    demandeur_id VARCHAR(64) NOT NULL,
    cible_id VARCHAR(64) NOT NULL,
    creneau_id BIGINT NOT NULL,
    stand_id VARCHAR(64) NOT NULL,
    motif TEXT,
    statut VARCHAR(32) NOT NULL DEFAULT 'PROPOSEE',
    -- Résultat de la prévalidation des contraintes dures au moment de la
    -- soumission : NULL tant que non évaluée, sinon faisable ou non, avec le
    -- détail métier (une description du catalogue de contraintes par ligne).
    prevalidation_ok BOOLEAN,
    contraintes_violees TEXT,
    commentaire_admin TEXT,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decide_le TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT demande_echange_demandeur_fkey
        FOREIGN KEY (edition_id, demandeur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT demande_echange_cible_fkey
        FOREIGN KEY (edition_id, cible_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE,
    -- creneau.id est global (BIGSERIAL, pas de clé composite — voir V34) :
    -- la FK ne porte que l'id.
    CONSTRAINT demande_echange_creneau_fkey
        FOREIGN KEY (creneau_id) REFERENCES creneau (id) ON DELETE CASCADE,
    CONSTRAINT demande_echange_stand_fkey
        FOREIGN KEY (edition_id, stand_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT demande_echange_statut_connu CHECK (
        statut IN ('PROPOSEE', 'ACCEPTEE', 'REFUSEE', 'ANNULEE')
    )
);

-- L'écran admin liste les demandes en attente, les plus récentes d'abord.
CREATE INDEX idx_demande_echange_statut ON demande_echange (edition_id, statut, cree_le DESC);
