'use strict';

/*
 * The order `Array#sort()` applies without a comparator — UTF-16 code units —
 * spelled out, for the scripts whose output (a report, a generated file) must
 * not move with the machine's locale the way `localeCompare` would.
 */
function byCodeUnit(a, b) {
  if (a < b) {
    return -1;
  }
  return a > b ? 1 : 0;
}

module.exports = { byCodeUnit };
