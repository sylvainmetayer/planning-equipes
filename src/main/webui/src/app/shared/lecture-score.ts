import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ReadingLink, ScoreSentence } from '../core/models';

/** A piece of a sentence: plain words, or the words a link sits on. */
export interface ReadingSegment {
  text: string;
  link: ReadingLink | null;
}

/**
 * Cuts a sentence at the words its links sit on. The server lists the links in
 * the order their words appear, so each one is searched from where the
 * previous one ended; a link whose words cannot be found — an older payload,
 * a label changed in between — is dropped rather than misplaced.
 */
export function segmentsOf(sentence: ScoreSentence): ReadingSegment[] {
  const segments: ReadingSegment[] = [];
  let cursor = 0;
  for (const link of sentence.liens ?? []) {
    const start = link.texte ? sentence.texte.indexOf(link.texte, cursor) : -1;
    if (start < 0) {
      continue;
    }
    if (start > cursor) {
      segments.push({ text: sentence.texte.slice(cursor, start), link: null });
    }
    segments.push({ text: link.texte, link });
    cursor = start + link.texte.length;
  }
  if (cursor < sentence.texte.length) {
    segments.push({ text: sentence.texte.slice(cursor), link: null });
  }
  return segments;
}

/**
 * « Lecture du score » — the diagnostic read out in a few sentences, above the
 * tables that detail it, for an organiser to whom « -1234medium » means
 * nothing. The sentences are written server-side, in French, from the
 * diagnostic itself (`ScoreReading`), so the Solveur page, the Diagnostic and
 * the Comparateur show the same text; this only turns the words of a rule
 * into a link to its line on the Contraintes screen and a day into a link to
 * its Journée. The list carries `lang="fr"`: the server does not translate
 * it, and a screen reader set to English must still pronounce it in French.
 *
 * Read-only: every link is a navigation.
 */
@Component({
  selector: 'app-lecture-score',
  imports: [RouterLink],
  template: `
    <div class="lecture-score" [attr.role]="hideHeading() ? null : 'region'" [attr.aria-labelledby]="hideHeading() ? null : headingId">
      @if (!hideHeading()) {
        <h2 class="lecture-score-titre" [id]="headingId" i18n="@@lectureScore.title">Lecture du score</h2>
      }
      @if (renderedSentences().length === 0) {
        <p class="lecture-score-vide" i18n="@@lectureScore.empty">Aucune résolution à lire.</p>
      } @else {
        @if (running()) {
          <p class="lecture-score-note" i18n="@@lectureScore.running">Une résolution est en cours : cette lecture porte sur le dernier plan analysé.</p>
        }
        <ul class="lecture-score-phrases" lang="fr">
          @for (sentence of renderedSentences(); track $index) {
            <li [class]="'lecture-score-phrase lecture-score-' + sentence.niveau.toLowerCase()">
              @for (segment of sentence.segments; track $index) {
                @if (segment.link; as link) {
                  <a [routerLink]="link.route" [queryParams]="link.parametres" [fragment]="link.fragment ?? undefined">{{ segment.text }}</a>
                } @else {
                  {{ segment.text }}
                }
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .lecture-score {
      margin: 0.5rem 0;
    }
    .lecture-score-titre {
      font: var(--mat-sys-title-small);
      margin: 0 0 0.25rem;
    }
    .lecture-score-phrases {
      margin: 0;
      padding-left: 1.25rem;
      font: var(--mat-sys-body-medium);
    }
    .lecture-score-phrase {
      margin: 0.125rem 0;
    }
    .lecture-score-bloquant {
      color: var(--mat-sys-error);
    }
    .lecture-score-attention {
      color: var(--mat-sys-tertiary);
    }
    .lecture-score-note,
    .lecture-score-vide {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
      margin: 0 0 0.25rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoreReadingPanel {
  private static sequence = 0;

  /** The sentences of the reading; null or empty when nothing has been analysed. */
  readonly sentences = input<ScoreSentence[] | null | undefined>(null);
  /** A solve is running: the reading describes the plan it is about to replace, and says so. */
  readonly running = input(false);
  /**
   * Drops the heading and the landmark, where the host already names the
   * reading — the Comparateur's `summary` — and a second name would only be
   * read twice.
   */
  readonly hideHeading = input(false);

  protected readonly headingId = `lecture-score-${++ScoreReadingPanel.sequence}`;

  protected readonly renderedSentences = computed(() =>
    (this.sentences() ?? []).map((sentence) => ({ ...sentence, segments: segmentsOf(sentence) })),
  );
}
