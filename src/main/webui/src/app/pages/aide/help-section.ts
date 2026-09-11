// The shape of one section of the organisers' guide. Its own module so the
// content files under `content/` and `aide-content.ts`, which assembles them,
// can both import it without importing each other.

import { HelpBlock } from '../../shared/help-blocks';

/**
 * Where the reader should go to act on what a section describes: an in-app
 * route, or an external destination (mailto:, GitHub) for the contact section.
 * Exactly one of `route`/`href` is set.
 */
export interface HelpLink {
  route?: string;
  /** With `route`: the rendering or the tab of a page gathering several screens (`?vue=`, `?onglet=`). */
  queryParams?: Record<string, string>;
  href?: string;
  label: string;
}

export interface HelpSection {
  /** Anchor id, also used as the `track` key. */
  id: string;
  icon: string;
  title: string;
  /** One-line answer to "what is this section about", shown under the title. */
  summary: string;
  blocks: HelpBlock[];
  links: HelpLink[];
}
