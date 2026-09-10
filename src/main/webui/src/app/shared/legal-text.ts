import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { segmenterArticles } from '../core/legifrance';

/**
 * French legal prose, with every article of the Code du travail it cites
 * turned into a link to Légifrance.
 *
 * <p>The constraint descriptions come from the backend `ConstraintCatalog` as
 * plain sentences — the same string that `/api/constraints` serves to the MCP
 * tools and stores on a swap request. They stay plain there: markup in a
 * domain description would leak into every other consumer. The link is added
 * here instead, at render time, by splitting the sentence around its article
 * numbers.</p>
 *
 * <p>Rendered as a list of text and anchor nodes rather than through
 * `[innerHTML]`: the backend text is never interpreted as markup, so a
 * description can say whatever it says without becoming an injection
 * surface.</p>
 */
@Component({
  selector: 'app-legal-text',
  template: `@for (segment of segments(); track $index) {
    @if (segment.url) {
      <a
        class="legal-article-link"
        [href]="segment.url"
        [attr.aria-label]="ariaLabel(segment.text)"
        target="_blank"
        rel="noopener"
        >{{ segment.text }}</a
      >
    } @else {
      <span>{{ segment.text }}</span>
    }
  }`,
  styles: `
    :host {
      display: inline;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LegalText {
  /** The sentence to render. Usually a `ConstraintView.description`. */
  readonly text = input('');

  protected readonly segments = computed(() => segmenterArticles(this.text()));

  /**
   * Read on its own by a screen reader, `L3121-20` says neither what it is nor
   * where the link goes; the surrounding sentence is a separate node.
   */
  protected ariaLabel(article: string): string {
    return $localize`:@@legalText.article.aria:Consulter l'article ${article}:article: sur Légifrance`;
  }
}
