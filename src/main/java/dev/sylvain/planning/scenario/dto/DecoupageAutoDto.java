package dev.sylvain.planning.scenario.dto;

/**
 * Marker section: its presence asks the import to auto-slice the scenario's
 * amplitudes into vacations, in place (issue #172 — the edition holds one
 * grid, there are no timeslot groups to name anymore). The canonical form is
 * an empty object ({@code decoupageAuto: {}}); the two historical fields are
 * still accepted so pre-#172 files import unchanged, but they are ignored.
 */
public record DecoupageAutoDto(String groupeSourceNom, String groupeCibleNom) {}
