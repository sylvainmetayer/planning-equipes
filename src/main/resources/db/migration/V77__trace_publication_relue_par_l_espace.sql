-- The publication trace, now read by the espace animateur too (issue #532).
--
-- Two columns, for the two things the trace could not say about a line it
-- stored.
--
-- 1. Was it a FIRST DELIVERY? A first delivery words the whole planning as
--    additions (« samedi 11/07 : Ninja 14h-18h (nouveau) »), which reads as a
--    list of corrections when it is in fact the planning itself. The mail
--    already told the two apart; the trace did not.
--
--    Lines written before this migration stay FALSE. Nothing can tell after
--    the fact whether a stored line announced a planning or a correction — and
--    guessing would mark an échange decision as a first delivery — so an
--    espace opened on a first delivery older than this migration lists its
--    « (nouveau) » sentences until the next publication rewrites the trace.
--    Truthful, and self-healing.
--
-- 2. Which half of the message was it? `changements` held both the schedule
--    lines and the échange lines, flattened, where the publication preview
--    has always kept them apart. They do not age the same way: a schedule
--    sentence is true forever — it says what was announced — while « votre
--    demande est en attente de décision » stops being true the moment the
--    organisation decides, without any publication having to happen. Replayed
--    in the espace, that line would contradict the live status of the same
--    request one tab away, which is the very contradiction issue #531 exists
--    to remove.
--
--    Existing lines keep everything in `changements`, as they were sent: split
--    afterwards, nothing tells a schedule sentence from an échange one.
ALTER TABLE publication_destinataire
    ADD COLUMN premiere_diffusion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN demandes JSONB;

COMMENT ON COLUMN publication_destinataire.premiere_diffusion IS
    'True when that publication was this person''s first: the lines describe a planning, not a list of changes';

COMMENT ON COLUMN publication_destinataire.demandes IS
    'The échange sentences of the same message, kept apart from the schedule ones: they age differently';
