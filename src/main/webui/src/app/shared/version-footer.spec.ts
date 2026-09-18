// The footer that answers « which version am I on? » on every screen.
//
// The version used to live on `/debug` alone — a screen an animateur never
// opens. What matters here is that the footer says the version in clear text
// (a bug report has to be able to quote it), that it links to that exact
// revision — the release page of a tag, the commit of a SHA — and that the
// link leaks nothing: the espace animateur's URL carries the access token, so
// `noreferrer` is not decoration.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { versionUrl } from '../core/version-link';
import { APP_VERSION } from '../version';
import { VersionFooter } from './version-footer';

function rendre() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  const fixture = TestBed.createComponent(VersionFooter);
  fixture.detectChanges();
  return fixture;
}

describe('VersionFooter', () => {
  it('affiche la version en clair', () => {
    const fixture = rendre();

    expect(fixture.nativeElement.textContent).toContain(APP_VERSION);
  });

  it('lie la version à la révision correspondante du dépôt', () => {
    const fixture = rendre();

    const lien = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    // `APP_VERSION` is whatever the build sits on — a tag on a release, a SHA
    // anywhere else — so the expectation goes through the same resolution the
    // footer uses; `version-link.spec.ts` pins what that resolution does.
    expect(lien.getAttribute('href')).toBe(versionUrl(APP_VERSION));
  });

  it('ouvre le dépôt sans emporter l’adresse de la page', () => {
    const fixture = rendre();

    // The espace animateur's URL carries the access token: it must not travel
    // in a Referer header, hence noreferrer and not only noopener.
    const lien = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(lien.getAttribute('rel')).toContain('noreferrer');
    expect(lien.getAttribute('rel')).toContain('noopener');
  });

  it('rend un repère de bas de page que les deux coquilles stylent', () => {
    const fixture = rendre();

    // A <footer> landmark, and the class the global partial styles: losing
    // either moves the version into the flow of the page above it.
    const pied = fixture.nativeElement.querySelector('footer') as HTMLElement;
    expect(pied).not.toBeNull();
    expect(pied.classList.contains('app-version-footer')).toBe(true);
  });
});
