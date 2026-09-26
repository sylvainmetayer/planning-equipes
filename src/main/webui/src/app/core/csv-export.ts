// « Exporter cette liste »: the rows a referential table displays — filtered,
// sorted, the columns it shows — written as the CSV a spreadsheet opens, built
// in the browser. Same conventions as the server's own exports
// (`api/CsvDownload.java`, `ReferentielCsvExportService`): a semicolon between
// fields, a pipe inside a multi-valued one, and a byte order mark so Excel
// reads the accents as UTF-8. Pure, except for the one call that saves it.

/** One column of an exported list: its header, and what a row writes under it. */
export interface CsvColumn<T> {
  title: string;
  value: (row: T) => string | number | boolean | null | undefined | readonly string[];
}

const SEPARATOR = ';';
const MULTI_SEPARATOR = '|';
const BOM = '﻿';

/**
 * A text a spreadsheet would run as a formula — « =… », « +… », « -… »,
 * « @… », a tab or a carriage return first — is written behind a quote, the
 * mark Excel and LibreOffice read as « this is text »: a name typed in a
 * form must not become a formula in the organiser's spreadsheet. Numbers are
 * left alone: a negative longitude is a number, not a formula. The paste of
 * a block takes the quote off again (`core/paste-rows.ts`), as the server's
 * CSV imports do: the rule is `CsvFormulaGuard` on that side.
 */
function neutralised(text: string): string {
  return /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
}

/** A field quoted when it must be: a separator, a quote or a line break inside it. */
function field(value: string | number | boolean | null | undefined | readonly string[]): string {
  if (value === null || value === undefined) {
    return '';
  }
  const text =
    typeof value === 'number' || typeof value === 'boolean'
      ? String(value)
      : neutralised(Array.isArray(value) ? value.join(MULTI_SEPARATOR) : String(value));
  return /[;"\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

/** The whole file, byte order mark first, one line per row, CRLF as Excel writes them. */
export function toCsv<T>(rows: readonly T[], columns: readonly CsvColumn<T>[]): string {
  const lines = [
    columns.map((column) => field(column.title)).join(SEPARATOR),
    ...rows.map((row) => columns.map((column) => field(column.value(row))).join(SEPARATOR)),
  ];
  return BOM + lines.join('\r\n') + '\r\n';
}

/** `typologies-2026-09-26.csv`: what the list is, and the day it was taken. */
export function csvFileName(base: string, now: Date = new Date()): string {
  const day = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
  ].join('-');
  return `${base}-${day}.csv`;
}

export const CSV_CONTENT_TYPE = 'text/csv;charset=utf-8';
