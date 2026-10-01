// Typed fakes of the services and stores of `core/`, for the specs.
//
// A page spec tests orchestration: what the page asks its services, and what
// it does with the answer. It used to provide `{ save: vi.fn(…) }` as the
// service, then read the calls back through `as unknown as [string, Item]` —
// a shape the spec declared and the compiler never checked against the
// service, so a renamed method or a changed argument kept the spec green.
// Here every member is typed on the class it fakes: a method is given as an
// implementation of its own signature and comes back as the Vitest mock of
// that signature, so `fake.save.mock.calls[0]` is the method's parameter
// tuple, and a signal stays the signal it was. `scripts/check-unknown-casts.js`
// holds the count of casts the specs still carry, file by file.

import { isSignal, Provider, ProviderToken, Signal } from '@angular/core';
import { Mock, vi } from 'vitest';

type Method = (...args: never[]) => unknown;

/** `T` as a fake holds it: a method is the mock of its own signature; a signal or a value is what `T` declares. */
export type Fake<T> = {
  [K in keyof T]: T[K] extends Signal<unknown>
    ? T[K]
    : T[K] extends Method
      ? Mock<Extract<T[K], (...args: never[]) => unknown>> & T[K]
      : T[K];
};

/**
 * A fake of `T` holding only the members a spec gives it, each typed as `T`
 * declares it. A function becomes a `vi.fn` of itself (a mock given already is
 * kept, a signal stays a signal); a member left out is absent, and a page
 * calling it fails on the call rather than on a silent `undefined`.
 */
export function fakeOf<T>(members: Partial<T>): Fake<T> {
  const fake: Record<string, unknown> = {};
  for (const [key, member] of Object.entries(members)) {
    fake[key] =
      typeof member === 'function' && !isSignal(member) && !vi.isMockFunction(member)
        ? vi.fn(member as (...args: unknown[]) => unknown)
        : member;
  }
  // The one assertion: the members given are the ones the spec relies on, and
  // each was checked against `T` on the way in.
  return fake as unknown as Fake<T>;
}

/** Provides `fake` for `token`, the fake typed on what the token injects. */
export function provideFake<T>(token: ProviderToken<T>, fake: Fake<T>): Provider {
  return { provide: token, useValue: fake };
}
