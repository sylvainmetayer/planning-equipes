package dev.sylvain.planning.domain;

/**
 * One seat of the last <b>published</b> plan, as a problem fact (issue
 * « stabilité »): the stand, the créneau and the animateur the people were
 * told about. Positional like every other reconciliation of the code base —
 * the seats of one stand on one créneau are interchangeable, so no poste id
 * is kept.
 *
 * <p>The list is empty until a plan has been published, and the constraint
 * reading it ({@code stabiliteDuPlanPublie}) is then silent: before the first
 * publication the solver stays free to reshuffle.</p>
 */
public record AffectationPubliee(String standId, Long creneauId, String animateurId) {}
