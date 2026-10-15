/**
 * dsh-mobile-presentation — Host half.
 *
 * This plugin is browser-only: all of its behaviour is a stylesheet injected by
 * the client half (`./client.js`). The host half exists so the package can be
 * mounted as a normal DSH bundle entry; it deliberately registers no services,
 * routes or settings namespace.
 */

export const name = 'dsh-mobile-presentation'

/** Nothing to do on the server. */
export function apply() {}
