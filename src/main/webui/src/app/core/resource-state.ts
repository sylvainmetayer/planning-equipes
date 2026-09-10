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
 */
export function retainedValue<T>(source: Resource<T | undefined>): Signal<T | null> {
  return linkedSignal<ResourceStatus, T | null>({
    source: source.status,
    computation: (_status, previous) => (source.hasValue() ? (source.value() as T) : (previous?.value ?? null))
  });
}

/**
 * The failure as the sentence the screen shows, empty while there is none.
 * Stays up while a reload is in flight, and clears on the next success.
 */
export function errorText(source: Resource<unknown>, format: (error: unknown) => string = errorPrefix): Signal<string> {
  return computed(() => {
    const error = source.error();
    return error ? format(error) : '';
  });
}
