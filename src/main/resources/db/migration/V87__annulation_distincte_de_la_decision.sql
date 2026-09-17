-- Une annulation n'est pas une décision de l'organisation (issue #540).
--
-- `DemandeEchangeService.cancel()` estampillait `decide_le` en passant la
-- demande à ANNULEE, alors que cette colonne porte le moment où l'organisation
-- a tranché. En aval, la publication ne pouvait plus les distinguer : elle
-- sélectionnait `decide_le IS NOT NULL`, et `libelleDecision` traitait « tout
-- ce qui n'est pas acceptée » comme un refus. L'animateur qui retirait sa
-- propre demande recevait donc « votre demande a été refusée », et cette ligne
-- suffisait à faire de lui un destinataire d'une publication qui n'avait rien
-- d'autre à lui dire.
--
-- La colonne `annule_le` sépare les deux moments. Les lignes déjà écrites sont
-- reportées : ce que `decide_le` porte sur une demande ANNULEE est une date
-- d'annulation, et elle repart donc dans la bonne colonne.

ALTER TABLE demande_echange
    ADD COLUMN IF NOT EXISTS annule_le TIMESTAMPTZ;

UPDATE demande_echange
SET annule_le = decide_le,
    decide_le = NULL
WHERE statut = 'ANNULEE'
  AND decide_le IS NOT NULL;
