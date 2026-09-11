// A fixture seam for the specs, and for nothing else.
//
// The stores of `core/` expose read-only signals: a page reads, the store
// writes, and `scripts/check-readonly-stores.js` (`npm run stores-check`)
// keeps it so. A spec that wants a store
// holding three animateurs used to write the signal straight; it now goes
// through here, which reaches the private writable signal behind the
// read-only view. Typed on the value, so a wrong fixture still fails to
// compile.

import { Signal, WritableSignal } from '@angular/core';

type ValueOf<S> = S extends Signal<infer T> ? T : never;

export function seedStore<S extends object, K extends keyof S & string>(
  store: S,
  key: K,
  value: ValueOf<S[K]>,
): void {
  const backing = (store as unknown as Record<string, WritableSignal<ValueOf<S[K]>> | undefined>)[
    '_' + key
  ];
  if (!backing || typeof backing.set !== 'function') {
    throw new Error(`${key} is not a read-only signal backed by _${key}: nothing to seed`);
  }
  backing.set(value);
}
