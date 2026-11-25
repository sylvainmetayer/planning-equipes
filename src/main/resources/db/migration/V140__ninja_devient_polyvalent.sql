-- 4.0 : la typologie « ninja » devient « polyvalente ». Le drapeau change de
-- nom, avec son index unique ; le nom de la règle (preserverBufferPolyvalents),
-- donc ses poids et son interrupteur, n'a jamais porté « ninja » : rien à
-- convertir de ce côté.
ALTER TABLE typologie RENAME COLUMN ninja TO polyvalent;
ALTER INDEX ux_typologie_ninja RENAME TO ux_typologie_polyvalent;

-- Le journal des actions garde les noms des champs modifiés, séparés par une
-- virgule : les lignes anciennes disent « polyvalent » comme les nouvelles.
UPDATE journal_action
   SET champs = regexp_replace(champs, '(^|,)ninja(,|$)', '\1polyvalent\2', 'g')
 WHERE champs ~ '(^|,)ninja(,|$)';
