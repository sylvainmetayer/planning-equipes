// What a read-only screen needs from a `resource()` beyond what it exposes.
//
// A resource answers three questions on its own — is it loading, did it fail,
// what did it resolve to — but `value()` throws in the error state and forgets
// the previous answer the moment a reload fails. A screen must not blank
// itself over a network blip (issue #392, B4): these two helpers keep the last
// good value on screen behind the failure message, and word that message the
// way every other screen does.

import { Resource, ResourceStatus, Signal, computed, linkedSignal } from '@angular/core';
import { errorPrefix } from './error-message';

/**
 * The last value the resource resolved to, `null` before the first one — and
 * kept across a failed reload, where the resource itself would throw.
 *
 * @param scope what the value is about, when a reload can be about something
 *              else — the day on screen, say: a change of scope drops the
 *              retained value rather than showing it under the new heading
 */
export function retainedValue<T>(
  source: Resource<T | undefined>,
  scope?: Signal<unknown>,
): Signal<T | null> {
  return linkedSignal<{ status: ResourceStatus; scope: unknown }, T | null>({
    source: () => ({ status: source.status(), scope: scope?.() }),
    computation: (current, previous) => {
      if (source.hasValue()) {
        return source.value() as T;
      }
      // Kept only within the scope it was read for: figures of another
      // day must not stand in while this one loads.
      return previous && previous.source.scope === current.scope ? previous.value : null;
    },
  });
}

/**
 * The failure as the sentence the screen shows, empty while there is none.
 * Stays up while a reload is in flight, and clears on the next success.
 */
export function errorText(
  source: Resource<unknown>,
  format: (error: unknown) => string = errorPrefix,
): Signal<string> {
  return computed(() => {
    const error = source.error();
    return error ? format(error) : '';
  });
}
