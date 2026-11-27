package dev.sylvain.planning.service.analyse;

/**
 * One reading of the Marge grid: the margin itself, before or after the solve,
 * or the tension map that crosses the « après » margin with the fragility.
 * What the MCP tools return — {@code consulter_besoin} (section {@code marge})
 * and {@code consulter_risques} (section {@code tension}) — sealed, so their
 * return types still name every shape they can send and the privacy check can
 * walk each of them.
 */
public sealed interface MarginReading permits MargeAnalyzer.RapportMarge, TensionAnalyzer.RapportTension {}
