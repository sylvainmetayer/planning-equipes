-- L'historique des pondérations rejoint le journal des actions : l'écran
-- Historique, filtre « Règles », dit quelle règle a changé de poids, de niveau
-- ou d'état, quand et par qui, sans la valeur (le journal n'en garde aucune).
--
-- Les anciennes lignes ne sont pas migrées dans le journal : elles portaient des
-- valeurs, qu'il refuse. La table est seulement renommée, gardée jusqu'à la 5.0
-- où elle sera supprimée (`DROP TABLE ponderation_contrainte_historique_avant_4_0`
-- pour le faire avant). Rien ne la lit ni ne l'exporte ; elle garde sa clé
-- étrangère, donc part avec la purge de son édition.
ALTER TABLE ponderation_contrainte_historique RENAME TO ponderation_contrainte_historique_avant_4_0;
