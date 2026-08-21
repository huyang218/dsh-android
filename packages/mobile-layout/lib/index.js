/**
 * DSH Plugin: handheld shell frame (host half).
 *
 * The work of this plugin is its client half. The host entry exists because
 * only an ENABLED loader entry is scanned for a `dsh.client` package — the row
 * is what makes the browser bundle be served at all.
 *
 * It therefore registers nothing: no tools, no listeners, no services.
 *
 * @module dsh-plugin-mobile-layout
 */

/** Cordis plugin name used by loader diagnostics. */
const name = 'mobile-layout';

/**
 * Mount point for the client bundle; deliberately empty on the host.
 * @returns {void}
 */
function apply() {}

export { apply, name };
