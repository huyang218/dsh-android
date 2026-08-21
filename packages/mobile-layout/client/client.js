/**
 * dsh-plugin-mobile-layout client bundle.
 *
 * Hand-written in the module loader's documented factory form
 * (`window.__ModuleLoader__.load({ id, factory })`) rather than produced by a
 * bundler, for the same reason as dsh-plugin-astock-chart: React and the
 * runtime arrive through the host-injected `require` table, so there is
 * nothing to bundle and a toolchain would buy nothing.
 *
 * WHAT THIS REPLACES. `@deepseek-ai/dsh-client-ui-layout` is not just the
 * three-column AppFrame — it is three responsibilities welded into one row:
 *
 *   1. the ONLY registration into the a-priori `root` slot, which declares the
 *      four child slots every other ui-* package registers into;
 *   2. the `layout` service (`ctx.reflect.provide`), injected by nine other
 *      packages in the rc.7 roster;
 *   3. the theme presenter, which projects `ctx.theme` snapshots onto the
 *      document — without it the whole client renders untokenized.
 *
 * Taking the frame therefore means taking all three. (2) and (3) are ported
 * near-verbatim on purpose: they are not where handheld differs, and a
 * divergence there would be a bug, not a design.
 *
 * WHAT ACTUALLY DIFFERS is (1): three resizable columns become one column with
 * an off-canvas drawer and a bottom sheet. The child-slot DECLARATIONS are
 * byte-identical to upstream's, which is what lets the rest of the roster load
 * unmodified — the mobile shell is a different frame around the same holes.
 */
window.__ModuleLoader__.load({
  id: 'dsh-plugin-mobile-layout',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const react = require('react')
    const runtime = require('@deepseek-ai/dsh-client-runtime/client')
    const h = react.createElement

    /** Dictionary namespace owned by this plugin (frame chrome copy). */
    const NS = 'mobile-layout'
    /** Frame copy. Registered through ctx.locale so the button follows locale/change live. */
    const zh = { openSidebar: '打开侧边栏', closeSidebar: '关闭侧边栏' }
    const en = { openSidebar: 'Open sidebar', closeSidebar: 'Close sidebar' }

    /** Drawer width when open. 0 is the closed state — the width IS the preference, as upstream. */
    const DRAWER_WIDTH = 300
    /** Sheet height when open, as a fraction of the viewport. */
    const SHEET_HEIGHT = 0.86
    /** Above this width a desktop dialog has room to be itself; below it, it does not. */
    const HANDHELD_MAX_WIDTH = 640
    /** How far in from the left edge a swipe may start and still mean "open the drawer". */
    const EDGE_ZONE = 24
    /** Horizontal travel that commits a swipe, in px. */
    const SWIPE_THRESHOLD = 48
    /** The smallest thing worth aiming a thumb at. */
    const TOUCH_TARGET = 44

    /**
     * Derive what the handheld frame shows from the panel store.
     *
     * Upstream encodes panel state as widths in px with 0 meaning closed. That
     * encoding is kept rather than replaced with booleans, so the shared action
     * set (`toggleSidebar` / `openDetails` / `closeDetails`) keeps one meaning
     * across both frames and `ctx.layout` callers need not know which frame is
     * mounted.
     * @param {{sidebar: number, details: number}} panels - store snapshot.
     * @returns {{drawerOpen: boolean, sheetOpen: boolean}} handheld view state.
     */
    function frameState(panels) {
      return {
        drawerOpen: (panels?.sidebar ?? 0) > 0,
        sheetOpen: (panels?.details ?? 0) > 0,
      }
    }

    /**
     * The handheld panel store: same shape and same action names as upstream's,
     * so the `layout` service face is identical.
     *
     * Two deliberate differences from the desktop store: the drawer starts
     * CLOSED (a phone has no room to show a sidebar next to anything), and the
     * widths are not clamped into a drag range because nothing drags — they are
     * open/closed flags that happen to be numbers.
     * @returns {object} the store handle.
     */
    function createLayoutStore() {
      return runtime.defineStore({
        init: () => ({ sidebar: 0, details: 0, narrow: true, narrowExpanded: false }),
        actions: {
          setSidebar: (d, px) => { d.sidebar = px > 0 ? DRAWER_WIDTH : 0 },
          setDetails: (d, px) => { d.details = px > 0 ? 1 : 0 },
          toggleSidebar: (d) => { d.sidebar = d.sidebar === 0 ? DRAWER_WIDTH : 0 },
          setNarrow: (d, narrow) => { d.narrow = narrow },
          openDetails: (d) => { d.details = 1 },
          closeDetails: (d) => { d.details = 0 },
        },
      })
    }

    /**
     * Cross-plugin panel-action face (`ctx.layout`). Ported from ui-layout: the
     * three methods below are the whole published surface, and nine packages in
     * the rc.7 roster call them.
     */
    class LayoutController {
      #panels
      /**
       * Adopt the root entry's bound store actions, from the registration's
       * inject hook, so the face is live from the entry's first render.
       * @param {object} actions - bound actions of the entry's store instance.
       */
      attachPanels(actions) { this.#panels = actions }
      /** Toggle the sidebar drawer. */
      toggleSidebar() { this.#require().toggleSidebar() }
      /** Open the details sheet (no-op when already open). */
      openDetails() { this.#require().openDetails() }
      /** Close the details sheet. */
      closeDetails() { this.#require().closeDetails() }
      #require() {
        if (this.#panels === undefined) throw new Error('layout: panel actions not wired (root entry not mounted)')
        return this.#panels
      }
    }

    /** Body attribute selecting the dark base palette in the token stylesheets. */
    const DARK_ATTRIBUTE = 'data-ds-dark-theme'

    /**
     * Applies theme snapshots to the document; one instance per plugin fiber.
     * Ported from ui-layout unchanged — the presenter is the client's only
     * writer of palette tokens, and handheld has no reason to differ.
     */
    class ThemePresenter {
      /** Token names written in the last apply (this presenter's retraction set). */
      appliedTokens = []
      /** The single metadata node this presenter inserts and removes. */
      themeColorMeta
      constructor() {
        this.themeColorMeta = document.createElement('meta')
        this.themeColorMeta.name = 'theme-color'
      }
      /**
       * Project a snapshot onto the document.
       * @param {object} snapshot - resolved theme snapshot from ctx.theme.
       */
      apply(snapshot) {
        const scheme = snapshot.active.colorScheme
        document.documentElement.style.colorScheme = scheme
        const body = document.body
        if (scheme === 'dark') body.setAttribute(DARK_ATTRIBUTE, '')
        else body.removeAttribute(DARK_ATTRIBUTE)
        for (const name of this.appliedTokens) body.style.removeProperty(name)
        this.appliedTokens = []
        for (const [name, value] of Object.entries(snapshot.active.tokens)) {
          body.style.setProperty(name, value)
          this.appliedTokens.push(name)
        }
        this.themeColorMeta.content = getComputedStyle(body).backgroundColor
        if (!this.themeColorMeta.isConnected) document.head.append(this.themeColorMeta)
      }
      /** Retract everything this presenter wrote. */
      dispose() {
        document.documentElement.style.removeProperty('color-scheme')
        const body = document.body
        body.removeAttribute(DARK_ATTRIBUTE)
        for (const name of this.appliedTokens) body.style.removeProperty(name)
        this.appliedTokens = []
        this.themeColorMeta.remove()
      }
    }

    /**
     * Frame styles, injected once under a guarded <style> tag — the same shape
     * the upstream CSS-module runtime emits, hand-written here because this
     * package has no build step.
     *
     * Only `--dsw-alias-*` / `--dsw-specific-*` semantic tokens are read and no
     * theme selector appears, per the upstream web-styling contract: light/dark
     * belongs to ui-theme, and the presenter above is what makes those tokens
     * exist on `body` at all.
     *
     * `100dvh` rather than `100vh` because mobile browser chrome retracts on
     * scroll; `env(safe-area-inset-*)` keeps the drawer and sheet clear of the
     * notch and the home indicator.
     *
     * THE DRAWER AND SHEET SLIDE ON `left`/`bottom`, NOT ON `transform`, and
     * that is not a style preference. A transform — even the identity matrix
     * of an open drawer — makes the element a containing block for every
     * `position: fixed` DESCENDANT. Upstream dialogs are rendered inside the
     * subtree that opened them, so the Settings overlay (`position: fixed`,
     * z-index 1000), opened from the button that lives in the sidebar, was
     * being clamped to the drawer's box: measured 300px wide inside a 412px
     * viewport, one word per line, its right half cut off. Anything modal
     * reachable from the sidebar has the same fate. The cost of the fix is
     * that these two transitions are not compositor-driven; on a 300px panel
     * that is not a real cost.
     */
    const css = `
.dshm-frame{position:relative;display:flex;flex-direction:column;height:100dvh;overflow:hidden;background:var(--dsw-alias-bg-base)}
.dshm-topbar{flex:none;display:flex;align-items:center;gap:4px;height:48px;padding:0 6px;
  padding-top:env(safe-area-inset-top);box-sizing:content-box;
  border-bottom:1px solid var(--dsw-alias-border-l1);background:var(--dsw-alias-bg-base)}
.dshm-menu{display:flex;align-items:center;justify-content:center;width:${TOUCH_TARGET}px;height:${TOUCH_TARGET}px;padding:0;
  border:none;border-radius:10px;background:none;color:var(--dsw-alias-label-primary);cursor:pointer}
.dshm-menu:hover,.dshm-menu:focus-visible{background:var(--dsw-alias-interactive-bg-hover)}
.dshm-main{flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden}
.dshm-scrim{position:absolute;inset:0;z-index:10;background:rgba(0,0,0,.45);opacity:0;pointer-events:none;transition:opacity .22s ease}
.dshm-frame[data-drawer-open] .dshm-scrim,.dshm-frame[data-sheet-open] .dshm-scrim{opacity:1;pointer-events:auto}
.dshm-drawer{--dshm-drawer-w:min(${DRAWER_WIDTH}px,86vw);
  position:absolute;top:0;bottom:0;z-index:11;width:var(--dshm-drawer-w);
  left:calc(-1 * (var(--dshm-drawer-w) + env(safe-area-inset-left) + 1px));
  padding-left:env(safe-area-inset-left);background:var(--dsw-specific-sidebar-fill);
  border-right:1px solid var(--dsw-alias-border-l1);overflow:hidden;
  transition:left .24s cubic-bezier(.2,.8,.2,1)}
.dshm-frame[data-drawer-open] .dshm-drawer{left:0}
.dshm-sheet{--dshm-sheet-h:${Math.round(SHEET_HEIGHT * 100)}dvh;
  position:absolute;left:0;right:0;z-index:12;height:var(--dshm-sheet-h);
  bottom:calc(-1 * (var(--dshm-sheet-h) + env(safe-area-inset-bottom) + 1px));
  padding-bottom:env(safe-area-inset-bottom);background:var(--dsw-alias-bg-base);
  border-top:1px solid var(--dsw-alias-border-l2);border-radius:14px 14px 0 0;overflow:hidden;
  transition:bottom .24s cubic-bezier(.2,.8,.2,1)}
.dshm-frame[data-sheet-open] .dshm-sheet{bottom:0}
.dshm-grip{display:block;width:36px;height:4px;margin:8px auto;border-radius:2px;background:var(--dsw-alias-border-l2)}
.dshm-overlay{position:absolute;inset:0;z-index:20;pointer-events:none}
.dshm-overlay>*{pointer-events:auto}
@media (prefers-reduced-motion:reduce){.dshm-drawer,.dshm-sheet,.dshm-scrim{transition:none}}
`
    /**
     * Inject one guarded stylesheet. A second call with the same id is a no-op,
     * so a remount does not stack sheets.
     * @param {string} id - stable identity, and the dedupe key.
     * @param {string} text - the sheet's source.
     */
    function injectStyle(id, text) {
      if (typeof document === 'undefined') return
      if (document.querySelector('style[data-plugin-css=' + JSON.stringify(id) + ']') !== null) return
      const tag = document.createElement('style')
      tag.dataset.plugin = 'dsh-plugin-mobile-layout'
      tag.dataset.pluginCss = id
      tag.textContent = text
      document.head.appendChild(tag)
    }

    /**
     * THE ONE SHEET THAT RESTYLES SOMEBODY ELSE'S PACKAGE — keep every such
     * rule here, never scattered into the packages themselves. That is the
     * upstream styling contract's rule (a feature package may not carry
     * viewport-specific chrome) applied to a frame we do not own: if this file
     * is the only place foreign selectors appear, "what does handheld override"
     * is one grep, and dropping the whole sheet returns the client to stock.
     *
     * WHAT IT FIXES. `ui-settings-general`'s Settings dialog is a desktop
     * shape: `.panel{width:800px;max-width:calc(100vw - 48px);display:flex}`
     * with a `flex:none;width:188px` nav column beside it. Measured at a 412px
     * viewport that leaves the content column 176px wide — one word per line,
     * the controls clipped. Turning the panel full-bleed and laying the nav out
     * as a scrollable tab strip along the top is the whole fix; nothing about
     * the settings content itself needs to change.
     *
     * WHY THESE SELECTORS. Upstream ships CSS modules, so the live class is
     * `<buildhash>_panel` — the hash moves with every build, the local name
     * does not. `[class*="_panel"]` therefore couples to upstream's SOURCE
     * identifiers, which is the most stable hook available from outside. It is
     * still a coupling: a local rename upstream silently drops these rules
     * (the dialog reverts to its desktop shape — visible, not broken), and any
     * other package whose overlay child is named `_panel` would be caught by
     * them too. The real fix is a media query upstream; this is what we can do
     * without forking.
     */
    const overrides = `
@media (max-width:${HANDHELD_MAX_WIDTH}px){
[class*="_overlay"]>[class*="_panel"]{width:100vw;max-width:100vw;height:100dvh;max-height:100dvh;
  border-radius:0;flex-direction:column;box-sizing:border-box;
  padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom)}
[class*="_overlay"]>[class*="_panel"]>nav{flex:none;flex-direction:row;width:auto;gap:4px;padding:8px 8px 0;
  overflow-x:auto;scrollbar-width:none;border-bottom:1px solid var(--dsw-alias-border-l1)}
[class*="_overlay"]>[class*="_panel"]>nav::-webkit-scrollbar{display:none}
[class*="_overlay"]>[class*="_panel"]>nav>[class*="_navTitle"]{display:none}
[class*="_overlay"]>[class*="_panel"]>nav>[class*="_navList"]{flex-direction:row;gap:4px}
[class*="_overlay"]>[class*="_panel"]>nav [class*="_navCell"]{white-space:nowrap}

/* Thumbs, not cursors. Upstream sizes its controls for a mouse — measured on
   this frame, 13 of them came in under 44px, several at 28. The visual weight
   is upstream's business; the hit area is not, and on a phone 44px is the
   floor. min-height rather than a padded overlay: an overlay needs
   position:relative on every button, and that would re-anchor any dropdown
   that positions itself against an ancestor. */
/* The [class] is not decoration: upstream's own rules are class selectors, so
   a bare "button" selector loses to them. Matching "a button that carries a
   CSS-module class" outranks them without naming one of those classes. */
button[class],[role="button"][class],input[class],select[class],textarea[class]{
  min-height:${TOUCH_TARGET}px;min-width:${TOUCH_TARGET}px}

/* Wide content must be reachable, not clipped. The conversation body is
   "overflow: hidden auto" — fine on a desktop column, but a 610px markdown
   table at 390px silently loses its right half. Let the wide things scroll
   themselves instead of widening the page. */
[class*="_scrollBody"] table{display:block;max-width:100%;overflow-x:auto}
[class*="_scrollBody"] pre{max-width:100%;overflow-x:auto}
}
`

    injectStyle('dsh-plugin-mobile-layout/frame.css', css)
    injectStyle('dsh-plugin-mobile-layout/overrides.css', overrides)

    /**
     * The handheld shell frame, registered into the built-in 'root' slot.
     *
     * One column owns the viewport; the sidebar and details occupants are the
     * same slots as on desktop, moved into an off-canvas drawer and a bottom
     * sheet. Both keep their subtree MOUNTED when closed (translated out of
     * view, not unmounted) — upstream's DetailsColumn does the same with width
     * 0, and unmounting would drop occupant state on every close.
     *
     * Pure component: everything arrives through the framework shares.
     * @param {object} props - the composed slot props.
     * @returns {object} the frame element.
     */
    function MobileFrame({ useStore, useSessions, actions, renderSlot, t }) {
      const panels = useStore((s) => s)
      const { drawerOpen, sheetOpen } = frameState(panels)
      const currentSession = useSessions((s) => s.current)
      const lastSession = react.useRef(currentSession)
      const frameRef = react.useRef(null)

      // Picking a session in the drawer must also LEAVE the drawer: a phone
      // shows one thing at a time, so the navigation and the destination
      // cannot both stay on screen. Desktop has no equivalent — its sidebar is
      // a permanent column, so upstream's AppFrame has nothing to dismiss.
      // Before paint, so the conversation is never revealed behind a drawer
      // that is still sliding away.
      react.useLayoutEffect(() => {
        if (currentSession === lastSession.current) return
        lastSession.current = currentSession
        actions.setSidebar(0)
        actions.closeDetails()
      }, [currentSession, actions])

      const dismiss = react.useCallback(() => {
        if (sheetOpen) actions.closeDetails()
        else actions.setSidebar(0)
      }, [actions, sheetOpen])

      // Edge swipe opens the drawer, a swipe back over it closes it. On a
      // phone the hamburger is a 44px target in one corner, and reaching it
      // one-handed is the whole reason drawers grew gestures.
      //
      // A THRESHOLD, NOT A RUBBER BAND: following the finger would mean moving
      // the panel with `transform`, and a transformed ancestor captures every
      // `position: fixed` descendant — the bug that trapped the Settings dialog
      // inside this drawer. Discrete open/close keeps that fix intact.
      react.useEffect(() => {
        const frame = frameRef.current
        if (!frame) return undefined
        let startX = 0
        let startY = 0
        let tracking = false

        const onStart = (event) => {
          if (event.touches.length !== 1) return
          const touch = event.touches[0]
          startX = touch.clientX
          startY = touch.clientY
          // Opening is an edge gesture so it cannot fight the content's own
          // horizontal scrolling; closing may start anywhere over the drawer.
          tracking = drawerOpen ? startX <= DRAWER_WIDTH : startX <= EDGE_ZONE
        }
        const onMove = (event) => {
          if (!tracking || event.touches.length !== 1) return
          const touch = event.touches[0]
          const dx = touch.clientX - startX
          const dy = touch.clientY - startY
          // Vertical intent wins: this must never steal a scroll.
          if (Math.abs(dy) > Math.abs(dx)) {
            tracking = false
            return
          }
          if (Math.abs(dx) < SWIPE_THRESHOLD) return
          tracking = false
          if (dx > 0 && !drawerOpen) actions.setSidebar(DRAWER_WIDTH)
          else if (dx < 0 && drawerOpen) actions.setSidebar(0)
        }
        const onEnd = () => { tracking = false }

        frame.addEventListener('touchstart', onStart, { passive: true })
        frame.addEventListener('touchmove', onMove, { passive: true })
        frame.addEventListener('touchend', onEnd, { passive: true })
        frame.addEventListener('touchcancel', onEnd, { passive: true })
        return () => {
          frame.removeEventListener('touchstart', onStart)
          frame.removeEventListener('touchmove', onMove)
          frame.removeEventListener('touchend', onEnd)
          frame.removeEventListener('touchcancel', onEnd)
        }
      }, [actions, drawerOpen])

      // The Android shell asks this before it lets a back press leave the app.
      // Without it, back skips straight past an open drawer or sheet and the
      // app appears to quit at random — the shell cannot see what the client
      // has open, and only the client can close it. Returning false means "not
      // mine", which is how the shell knows to fall back to history.
      react.useEffect(() => {
        const previous = window.__dshmBack
        window.__dshmBack = () => {
          if (sheetOpen) {
            actions.closeDetails()
            return true
          }
          if (drawerOpen) {
            actions.setSidebar(0)
            return true
          }
          return false
        }
        return () => { window.__dshmBack = previous }
      }, [actions, drawerOpen, sheetOpen])
      const toggleDrawer = react.useCallback(() => { actions.toggleSidebar() }, [actions])

      return h('div', {
        ref: frameRef,
        className: 'dshm-frame',
        'data-drawer-open': drawerOpen || undefined,
        'data-sheet-open': sheetOpen || undefined,
      }, [
        h('div', { key: 'topbar', className: 'dshm-topbar' },
          h('button', {
            type: 'button',
            className: 'dshm-menu',
            onClick: toggleDrawer,
            'aria-expanded': drawerOpen,
            'aria-label': t(drawerOpen ? 'closeSidebar' : 'openSidebar'),
          }, h('svg', {
            width: 20, height: 20, viewBox: '0 0 20 20', fill: 'none',
            stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', 'aria-hidden': true,
          }, [
            h('path', { key: 'a', d: 'M3 5.5h14' }),
            h('path', { key: 'b', d: 'M3 10h14' }),
            h('path', { key: 'c', d: 'M3 14.5h14' }),
          ]))),
        h('div', { key: 'main', className: 'dshm-main' }, renderSlot('conversation', {})),
        h('div', { key: 'scrim', className: 'dshm-scrim', onClick: dismiss }),
        h('div', { key: 'drawer', className: 'dshm-drawer' },
          renderSlot('sidebar', { collapsed: !drawerOpen, width: DRAWER_WIDTH })),
        h('div', { key: 'sheet', className: 'dshm-sheet' }, [
          h('span', { key: 'grip', className: 'dshm-grip' }),
          renderSlot('details', {}),
        ]),
        h('div', { key: 'overlay', className: 'dshm-overlay', 'data-shell-overlay': true },
          renderSlot('shell.overlay', {})),
      ])
    }

    /**
     * Required services (cordis fiber inject). ui-layout's two, plus `locale`:
     * the handheld frame owns chrome of its own (the drawer toggle), and frame
     * copy goes through the shared dictionary registry rather than inline text.
     */
    const inject = ['slots', 'theme', 'locale']

    /**
     * Client plugin body: provide ctx.layout, one register() call for the root
     * frame, and the theme presenter — the three responsibilities inherited
     * from ui-layout, in its own order.
     * @param {object} ctx - client root context.
     */
    function apply(ctx) {
      ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'mobile-layout: dictionaries')
      const layout = new LayoutController()
      ctx.effect(() => {
        const disposeService = ctx.reflect.provide('layout', layout)
        const disposeRegistration = ctx.slots.register({
          name: 'root',
          locale: NS,
          children: {
            'sidebar': { kind: 'single', scope: 'root' },
            'conversation': { kind: 'single', scope: 'session-maybe' },
            'details': { kind: 'single', scope: 'session' },
            'shell.overlay': { kind: 'list', scope: 'root' },
          },
          store: createLayoutStore,
          inject: (actions) => {
            layout.attachPanels(actions)
            return {}
          },
        }, MobileFrame)
        return () => {
          disposeRegistration()
          disposeService()
        }
      }, 'mobile-layout: service + root registration')

      ctx.effect(() => {
        const presenter = new ThemePresenter()
        presenter.apply(ctx.theme.getTheme())
        const off = ctx.on('theme/change', (snapshot) => { presenter.apply(snapshot) })
        return () => {
          off()
          presenter.dispose()
        }
      }, 'mobile-layout: theme presenter')
    }

    exports.LayoutController = LayoutController
    exports.frameState = frameState
    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})
