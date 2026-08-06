-- Second weekly working-time ceiling, specific to minors ("jeunes
-- travailleurs"). Default is 35h (2100 min), set by the Code du travail
-- art. L3162-1 ("Les jeunes travailleurs ne peuvent être employés à un travail
-- effectif excédant huit heures par jour et trente-cinq heures par semaine")
-- and, for the 14-to-under-16 bracket employed during school holidays, by
-- art. D4153-3.
--
-- Until this column existed, minors were capped by the majeur ceiling (48h),
-- i.e. 13h/week above the ordre public maximum protecting them.

ALTER TABLE parametres_legaux
    ADD COLUMN IF NOT EXISTS duree_hebdomadaire_max_mineur_minutes INTEGER NOT NULL DEFAULT 2100;
