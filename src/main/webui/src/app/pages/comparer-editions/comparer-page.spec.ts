// « Comparer deux versions »: the host of the two views. What only it decides:
// which view an address opens, and that the view chosen is the one drawn.

import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ComparerEditionsPage } from './comparer-editions-page';
import { ComparerPage, readComparerView } from './comparer-page';
import { ComparerVersionsView } from './comparer-versions-view';

@Component({ selector: 'app-comparer-editions-page', template: '<p>editions</p>' })
class FakeEditions {}

@Component({ selector: 'app-comparer-versions-view', template: '<p>versions</p>' })
class FakeVersions {}

describe('readComparerView', () => {
  it('opens the editions on their value, the plans on anything else', () => {
    expect(readComparerView('editions')).toBe('editions');
    expect(readComparerView('versions')).toBe('versions');
    expect(readComparerView(null)).toBe('versions');
    expect(readComparerView('autre')).toBe('versions');
  });
});

describe('ComparerPage', () => {
  function render() {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    TestBed.overrideComponent(ComparerPage, {
      remove: { imports: [ComparerEditionsPage, ComparerVersionsView] },
      add: { imports: [FakeEditions, FakeVersions] },
    });
    const fixture = TestBed.createComponent(ComparerPage);
    return fixture;
  }

  it('draws the plans first, and the editions once the view is chosen', async () => {
    const fixture = render();
    await fixture.whenStable();
    const racine = fixture.nativeElement as HTMLElement;
    expect(racine.querySelector('h1')?.textContent).toContain('Comparer deux versions');
    expect(racine.textContent).toContain('versions');
    expect(racine.querySelector('app-comparer-editions-page')).toBeNull();

    await fixture.componentInstance['views'].changer('editions');
    await fixture.whenStable();
    expect(racine.querySelector('app-comparer-editions-page')).not.toBeNull();
    expect(racine.querySelector('app-comparer-versions-view')).toBeNull();
  });
});
