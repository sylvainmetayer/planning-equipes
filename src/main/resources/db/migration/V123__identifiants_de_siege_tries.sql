-- Les identifiants de siège sont désormais engendrés sur six chiffres
-- (`poste-000017` au lieu de `poste-17`, ProblemBuilder.seatId).
--
-- La colonne est un VARCHAR relu par `ORDER BY id`, et le réamorçage est
-- positionnel : `poste-10` passait avant `poste-2`. Dès que les sièges d'un
-- même stand × créneau enjambaient un palier décimal et portaient des
-- fenêtres différentes (stand partiellement fermé), quelqu'un revenait sur le
-- siège voisin, avec d'autres heures — y compris sur une journée verrouillée.
--
-- Le plan en place n'est réécrit qu'au prochain calcul, qui commence
-- justement par le relire : on le réécrit ici, `suite_de` compris, pour que
-- ce premier calcul lise ses sièges dans l'ordre où ils ont été engendrés. Le
-- suffixe d'une scission (`~0920`, ADR 0066) est conservé. Les instantanés
-- (`plan_snapshot.contenu`) ne sont pas touchés : une restauration complète
-- les identifiants qu'elle remet en place (ProblemBuilder.paddedSeatId).
--
-- Aucune collision possible : un ancien identifiant n'a jamais de zéro en
-- tête (sauf `poste-0`), et un identifiant déjà sur six chiffres n'est pas
-- concerné.
UPDATE poste_affectation
SET id = 'poste-' || lpad(substring(id FROM '^poste-([0-9]+)'), 6, '0')
        || substring(id FROM '^poste-[0-9]+(.*)$')
WHERE id ~ '^poste-[0-9]{1,5}([^0-9]|$)';

UPDATE poste_affectation
SET suite_de = 'poste-' || lpad(substring(suite_de FROM '^poste-([0-9]+)'), 6, '0')
        || substring(suite_de FROM '^poste-[0-9]+(.*)$')
WHERE suite_de ~ '^poste-[0-9]{1,5}([^0-9]|$)';
