-- Concurrent-edit detection on the referential (issue #362): every business
-- row of the six referentials remembers when it was last written. A form
-- sends back the value it loaded; a write whose value no longer matches is
-- refused (409) instead of silently overwriting what another session wrote.
--
-- Bumped by every referential write (service update, import, bulk edit) and
-- deliberately left alone by the landing persist of a solve, which rewrites
-- the referential as it stood when the problem was built: nothing changed
-- there, and bumping it would make every open form conflict after every solve.
--
-- Not a version counter: a timestamp is what the message shows the user
-- (« modifiée le … à … ») and it costs the same to compare.
ALTER TABLE stand ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE animateur ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE creneau ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE typologie ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE emplacement ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE contrainte_ad_hoc ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
