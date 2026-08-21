/**
 * Tests for the shipped client bundle's pure helpers.
 *
 * The bundle is a `window.__ModuleLoader__.load({ id, factory })` call, so the
 * test stubs that global, captures the factory, and runs it with a `require`
 * table — exercising the artifact that actually ships rather than a copy of
 * its logic. Only the pure exports are asserted here; the frame component and
 * the two document-writing classes need a DOM.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const bundlePath = fileURLToPath(new URL('../client/client.js', import.meta.url))

/** Load the bundle and return its module exports. */
function loadBundle() {
  let captured
  const priorWindow = globalThis.window
  const priorDocument = globalThis.document
  globalThis.window = { __ModuleLoader__: { load: (spec) => { captured = spec } } }
  // The bundle injects its stylesheet at factory time; a null query result and
  // a head that swallows appends is the whole DOM surface that path touches.
  const styles = []
  globalThis.document = {
    querySelector: () => null,
    createElement: () => {
      const tag = { dataset: {}, style: {}, remove() {} }
      styles.push(tag)
      return tag
    },
    head: { appendChild() {}, append() {} },
  }
  loadBundle.styles = styles
  try {
    // eslint-disable-next-line no-eval
    ;(0, eval)(readFileSync(bundlePath, 'utf8'))
    return captured.factory((id) => {
      if (id === 'react') return { createElement: () => ({}), useCallback: (fn) => fn }
      if (id === '@deepseek-ai/dsh-client-runtime/client') return { defineStore: (spec) => spec }
      throw new Error(`unexpected require: ${id}`)
    })
  } finally {
    globalThis.window = priorWindow
    globalThis.document = priorDocument
  }
}

test('bundle registers under its package id', () => {
  const priorWindow = globalThis.window
  let spec
  globalThis.window = { __ModuleLoader__: { load: (s) => { spec = s } } }
  globalThis.document = { querySelector: () => null, createElement: () => ({ dataset: {}, style: {}, remove() {} }), head: { appendChild() {}, append() {} } }
  try {
    ;(0, eval)(readFileSync(bundlePath, 'utf8'))
  } finally {
    globalThis.window = priorWindow
  }
  assert.equal(spec.id, 'dsh-plugin-mobile-layout')
})

test('frameState reads upstream width encoding, 0 = closed', () => {
  const { frameState } = loadBundle()
  assert.deepEqual(frameState({ sidebar: 0, details: 0 }), { drawerOpen: false, sheetOpen: false })
  assert.deepEqual(frameState({ sidebar: 300, details: 0 }), { drawerOpen: true, sheetOpen: false })
  assert.deepEqual(frameState({ sidebar: 0, details: 1 }), { drawerOpen: false, sheetOpen: true })
})

test('frameState treats a missing snapshot as fully closed', () => {
  const { frameState } = loadBundle()
  assert.deepEqual(frameState(undefined), { drawerOpen: false, sheetOpen: false })
  assert.deepEqual(frameState({}), { drawerOpen: false, sheetOpen: false })
})

test('the layout face throws before the root entry wires its actions', () => {
  const { LayoutController } = loadBundle()
  const layout = new LayoutController()
  assert.throws(() => layout.toggleSidebar(), /panel actions not wired/)
})

test('the layout face forwards the three published methods once attached', () => {
  const { LayoutController } = loadBundle()
  const calls = []
  const layout = new LayoutController()
  layout.attachPanels({
    toggleSidebar: () => calls.push('toggleSidebar'),
    openDetails: () => calls.push('openDetails'),
    closeDetails: () => calls.push('closeDetails'),
  })
  layout.toggleSidebar()
  layout.openDetails()
  layout.closeDetails()
  assert.deepEqual(calls, ['toggleSidebar', 'openDetails', 'closeDetails'])
})

test('inject matches the services the plugin body uses', () => {
  const { inject } = loadBundle()
  // slots + theme are inherited from ui-layout's responsibilities; locale is
  // this frame's own, for the drawer toggle it owns and upstream does not.
  assert.deepEqual(inject, ['slots', 'theme', 'locale'])
})

test('the drawer and sheet never slide on transform', () => {
  loadBundle()
  const css = loadBundle.styles.map((tag) => tag.textContent ?? '').join('\n')
  const frameRules = css
    .split('\n')
    .filter((line) => line.startsWith('.dshm-drawer') || line.startsWith('.dshm-sheet') || line.includes('data-drawer-open') || line.includes('data-sheet-open'))
    .join('\n')
  assert.notEqual(frameRules, '')
  // A transform on either panel — including the identity one an open drawer
  // would carry — makes it the containing block for `position: fixed`
  // descendants, and every upstream dialog opened from the sidebar renders
  // inside that subtree. Settings was measured clamped to the 300px drawer.
  assert.equal(/transform/.test(frameRules), false, 'a transformed panel traps the fixed dialogs opened inside it')
})

test('foreign-package overrides stay inside the handheld media query', () => {
  loadBundle()
  const sheets = loadBundle.styles.map((tag) => tag.textContent ?? '')
  const foreign = sheets.filter((text) => text.includes('[class*='))
  assert.equal(foreign.length, 1, 'overrides of other packages belong in exactly one sheet')
  const [sheet] = foreign
  // Every foreign selector must be gated on width. Unguarded, these rules would
  // reshape the desktop client too — this package is loaded there as well when
  // someone opens the handheld profile on a laptop.
  assert.match(sheet, /@media \(max-width:640px\)/)
  const outsideMedia = sheet.replace(/@media[^{]*\{[\s\S]*\}/, '')
  assert.equal(outsideMedia.includes('[class*='), false, 'a foreign selector escaped the width gate')
})
