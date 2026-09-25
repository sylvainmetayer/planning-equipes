package dev.sylvain.planning.service.referentiel;

import java.time.Instant;

/**
 * One family of the referential frozen in the current edition.
 *
 * @param famille which family
 * @param figeLe  when the organiser froze it — what the forms show beside the
 *                padlock
 */
public record GelReferentiel(ReferentialFamily famille, Instant figeLe) {}
