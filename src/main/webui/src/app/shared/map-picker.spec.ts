// Characterisation tests for the Leaflet location picker.
//
// The point of interest is the interaction between the two `effect`s and the
// readiness guard: the effects run once before `ngAfterViewInit`, when there is
// no map yet, and bail out. What matters for the user is that a locked form
// never hands out a draggable marker, whatever order those two things happen in.

import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { MapPicker } from './map-picker';

@Component({
  selector: 'app-map-picker-host',
  imports: [MapPicker],
  template: `<app-map-picker
    [latitude]="latitude()"
    [longitude]="longitude()"
    [disabled]="disabled()"
  />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  readonly latitude = signal<number | null>(null);
  readonly longitude = signal<number | null>(null);
  readonly disabled = signal(false);
}

/** Leaflet needs a laid-out container; jsdom reports zero, which it tolerates. */
function mount() {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const picker = fixture.debugElement.children[0].componentInstance as MapPicker;
  return { fixture, picker };
}

/** The private marker, reached deliberately: its drag state is the whole subject. */
function marker(picker: MapPicker): { dragging?: { enabled(): boolean } } | undefined {
  return (picker as unknown as { marker?: { dragging?: { enabled(): boolean } } }).marker;
}

describe('MapPicker', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  it('does not offer a draggable marker when the form is locked from the very first render', () => {
    const { fixture, picker } = mount();
    const host = fixture.componentInstance;
    host.disabled.set(true);
    host.latitude.set(47.2);
    host.longitude.set(-1.55);
    fixture.detectChanges();

    expect(marker(picker)?.dragging?.enabled()).toBe(false);
  });

  it('locks an already-placed marker when the form becomes locked', () => {
    const { fixture, picker } = mount();
    const host = fixture.componentInstance;
    host.latitude.set(47.2);
    host.longitude.set(-1.55);
    fixture.detectChanges();
    expect(marker(picker)?.dragging?.enabled()).toBe(true);

    host.disabled.set(true);
    fixture.detectChanges();

    expect(marker(picker)?.dragging?.enabled()).toBe(false);
  });

  it('unlocks the marker again when the form is unlocked', () => {
    const { fixture, picker } = mount();
    const host = fixture.componentInstance;
    host.disabled.set(true);
    host.latitude.set(47.2);
    host.longitude.set(-1.55);
    fixture.detectChanges();

    host.disabled.set(false);
    fixture.detectChanges();

    expect(marker(picker)?.dragging?.enabled()).toBe(true);
  });

  it('places the marker on the coordinates typed into the numeric inputs', () => {
    const { fixture, picker } = mount();
    const host = fixture.componentInstance;
    host.latitude.set(47.2);
    host.longitude.set(-1.55);
    fixture.detectChanges();

    const placed = (picker as unknown as { marker?: { getLatLng(): { lat: number; lng: number } } })
      .marker;
    expect(placed?.getLatLng().lat).toBeCloseTo(47.2);
    expect(placed?.getLatLng().lng).toBeCloseTo(-1.55);
  });
});
