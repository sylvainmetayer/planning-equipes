/**
 * The params with the empty ones left out: an empty `du` is no bound, not a
 * date the server would refuse. Built from a `URLSearchParams` literal so that
 * `api-contract-check` can still read every parameter name.
 */
export function dropEmpty(params: URLSearchParams): void {
  // Collected first: deleting while iterating the live params skips an entry.
  const empty: string[] = [];
  params.forEach((value, key) => {
    if (!value) {
      empty.push(key);
    }
  });
  for (const key of empty) {
    params.delete(key);
  }
}
