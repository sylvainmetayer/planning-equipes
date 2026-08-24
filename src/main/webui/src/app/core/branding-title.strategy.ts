// Page titles: "<page> — <product>", the product coming from the deployment.
//
// The routes declare the page and nothing else. Hard-coding the suffix in
// thirty-five route definitions is what made renaming the product a
// thirty-five-line diff, and what made a white-label deployment impossible: a
// title is built once, here, from the configuration.

import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

import { BRANDING } from './branding';

@Injectable({ providedIn: 'root' })
export class BrandingTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly branding = inject(BRANDING);

  /**
   * The separator is an em dash on purpose: the admin shell announces a
   * navigation by reading back everything before it, so the screen reader says
   * the page and not the product name after it.
   */
  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot);
    this.title.setTitle(page ? `${page} — ${this.branding.productName}` : this.branding.productName);
  }
}
