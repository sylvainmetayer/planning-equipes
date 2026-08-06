-- `pasDeDoubleAffectationSurMemeCreneau` a été remplacée par
-- `pasDeChevauchementHoraire` (détection réelle du chevauchement horaire, et
-- plus seulement de l'identité de créneau).
--
-- Une ligne dans constraint_toggle signifie « contrainte désactivée ». Une
-- ligne portant l'ancien nom ne correspondrait plus à aucune contrainte : elle
-- resterait invisible dans l'IHM tout en restant en base. On la reporte sur le
-- nouveau nom pour ne pas réactiver silencieusement une règle qu'un
-- administrateur avait choisi de désactiver.

INSERT INTO constraint_toggle (nom)
SELECT 'pasDeChevauchementHoraire'
WHERE EXISTS (SELECT 1 FROM constraint_toggle WHERE nom = 'pasDeDoubleAffectationSurMemeCreneau')
ON CONFLICT (nom) DO NOTHING;

DELETE FROM constraint_toggle WHERE nom = 'pasDeDoubleAffectationSurMemeCreneau';
