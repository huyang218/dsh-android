/**
 * Web search through Tencent Cloud's 联网搜索 API (product `wsa`), registered
 * into the same `ctx.web` seam the shipped DeepSeek provider uses.
 *
 * <p>Why a second provider rather than a replacement: `dsh-web` selects by
 * configured id among the registered providers, so both can be mounted and the
 * choice is one line of composition. The DeepSeek row stays registered as a
 * fallback — losing a key should not mean losing search.
 *
 * <p><b>Two credentials, not one.</b> Tencent signs every request with a
 * SecretId/SecretKey pair (TC3-HMAC-SHA256), so this cannot reuse the single
 * bearer token the DeepSeek provider carries. Both are resolved per call
 * through the credentials service and never retained on the provider.
 *
 * <p><b>The shape of a result is unusual and worth stating.</b> `Pages` comes
 * back as an array of JSON STRINGS, not objects — each string parses into
 * `{title, url, date, passage, content, site, score, ...}`. A page that fails
 * to parse is skipped rather than failing the search: one malformed row should
 * not cost the user every other result.
 *
 * <p>Reference: https://cloud.tencent.com/document/product/1806/121811
 *
 * @module dsh-plugin-web-search-tencent
 */

import z from '@deepseek-ai/schemastery'
import { credentialRef } from '@deepseek-ai/dsh-credentials'
import { installSettingsSection, settingsNamespace } from '@deepseek-ai/dsh-settings'
import { launchEnvironmentOf } from '@deepseek-ai/dsh-launch-environment'
import { WebError } from '@deepseek-ai/dsh-web'

import { authorization } from './sign.js'
import { TencentSearchError, mapSearchProResponse, toSource } from './map.js'

/** Stable id this provider registers under; name it in `web.searchProvider` to select it. */
const TENCENT_PROVIDER_ID = 'tencent-wsa'

/** Cordis plugin name used by loader diagnostics. */
const name = 'web-search-tencent'

/** The seam this provider registers into. */
const inject = ['web']

const DEFAULT_HOST = 'wsa.tencentcloudapi.com'
const DEFAULT_ACTION = 'SearchPro'
/** Pinned: a version bump can change the response shape, so it is a decision, not a default to drift. */
const DEFAULT_API_VERSION = '2025-05-08'
const DEFAULT_SERVICE = 'wsa'
const DEFAULT_SECRET_ID_ENV = 'TENCENTCLOUD_SECRET_ID'
const DEFAULT_SECRET_KEY_ENV = 'TENCENTCLOUD_SECRET_KEY'
const USER_AGENT = 'dsh-android/web-search-tencent'

const Config = z.object({
  secretId: z.string().role('secret'),
  secretKey: z.string().role('secret'),
  secretIdEnv: z.string().role('credential-ref').default(DEFAULT_SECRET_ID_ENV),
  secretKeyEnv: z.string().role('credential-ref').default(DEFAULT_SECRET_KEY_ENV),
  host: z.string().default(DEFAULT_HOST),
  action: z.string().default(DEFAULT_ACTION),
  apiVersion: z.string().default(DEFAULT_API_VERSION),
  // 0 = 全网结果, 1 = 优质垂类, 2 = 混合。混合只有标准版以上支持,所以默认最保守的 0。
  mode: z.number().step(1).min(0).max(2).default(0),
  // Cnt only applies on 高级版/旗舰版; on lower tiers the field is ignored
  // rather than rejected, so sending it is safe.
  count: z.number().step(1).min(10).max(50).default(10)
})

/** Settings namespace, so the Plugins page can carry the keys and the tier options. */
const WEB_SEARCH_TENCENT_SETTINGS_NAMESPACE = settingsNamespace('web-search-tencent')

/** The Tencent-backed search provider. */
class TencentSearchProvider {
  id = TENCENT_PROVIDER_ID

  /**
   * @param {() => object} resolveOptions - options for the NEXT search,
   *   snapshotted at entry so one search never mixes two settings revisions —
   *   the same discipline the shipped DeepSeek provider follows.
   */
  constructor(resolveOptions) {
    this.resolveOptions = resolveOptions
  }

  available() {
    const options = this.resolveOptions()
    const hasLiteral = (options.secretId?.length ?? 0) > 0 && (options.secretKey?.length ?? 0) > 0
    return (hasLiteral || options.resolveCredentials !== undefined) && options.host.length > 0
  }

  async search(request, signal) {
    const options = this.resolveOptions()
    const { secretId, secretKey } = await this.credentials(options)
    if (signal?.aborted === true) throw new WebError('search aborted', 'WEB_SEARCH_ABORTED')

    const payload = JSON.stringify({
      Query: request.query,
      Mode: options.mode,
      Cnt: options.count
    })
    // Seconds, and Tencent rejects anything more than five minutes from its own
    // clock — a phone with a wrong clock fails here with a signature error.
    const timestamp = Math.floor(Date.now() / 1000)

    let response
    try {
      response = await fetch(`https://${options.host}/`, {
        method: 'POST',
        redirect: 'error',
        headers: {
          'authorization': authorization({
            secretId,
            secretKey,
            service: options.service,
            host: options.host,
            action: options.action,
            payload,
            timestamp
          }),
          'content-type': 'application/json; charset=utf-8',
          'host': options.host,
          'x-tc-action': options.action,
          'x-tc-version': options.apiVersion,
          'x-tc-timestamp': String(timestamp),
          'user-agent': USER_AGENT
        },
        body: payload,
        ...(signal === undefined ? {} : { signal })
      })
    } catch (error) {
      if (signal?.aborted === true) throw new WebError('search aborted', 'WEB_SEARCH_ABORTED', { cause: error })
      throw new WebError(`Tencent search request failed: ${String(error)}`, 'WEB_PROVIDER_ERROR', { cause: error })
    }

    if (!response.ok) {
      throw new WebError(`Tencent API error (HTTP ${response.status})`, 'WEB_PROVIDER_ERROR')
    }
    let body
    try {
      body = await response.json()
    } catch (error) {
      throw new WebError(`Tencent returned an unprocessable body: ${String(error)}`, 'WEB_PROVIDER_ERROR', { cause: error })
    }
    try {
      return mapSearchProResponse(body, options.count)
    } catch (error) {
      // The mapper is deliberately dependency-free, so it throws its own error
      // type; the seam only understands WebError.
      if (error instanceof TencentSearchError) {
        throw new WebError(error.message, 'WEB_PROVIDER_ERROR', { cause: error })
      }
      throw error
    }
  }

  /**
   * Resolve the pair for one call without retaining it on the provider.
   * @param {object} options - the snapshotted options.
   * @returns {Promise<{secretId: string, secretKey: string}>} the credentials.
   */
  async credentials(options) {
    const literalId = options.secretId
    const literalKey = options.secretKey
    if ((literalId?.length ?? 0) > 0 && (literalKey?.length ?? 0) > 0) {
      return { secretId: literalId, secretKey: literalKey }
    }
    const resolved = await options.resolveCredentials?.()
    if (resolved === undefined || resolved.secretId === undefined || resolved.secretKey === undefined) {
      throw new WebError(
        `no Tencent credentials; store ${options.secretIdEnv} and ${options.secretKeyEnv} ` +
          'through the credentials service, or export them in the launching environment',
        'WEB_PROVIDER_UNAVAILABLE'
      )
    }
    return resolved
  }
}

/**
 * Project one settings revision into the options a search runs with.
 * @param {object} ctx - plugin context supplying credentials and environment.
 * @param {object} config - the authoritative section.
 * @returns {object} options for one search.
 */
function resolveOptions(ctx, config) {
  const secretIdEnv = credentialRef(config.secretIdEnv ?? DEFAULT_SECRET_ID_ENV)
  const secretKeyEnv = credentialRef(config.secretKeyEnv ?? DEFAULT_SECRET_KEY_ENV)
  const readOne = async (ref) => {
    const credentials = ctx.get('credentials')
    if (credentials !== undefined) return (await credentials.resolve(ref))?.value
    const ambient = launchEnvironmentOf(ctx).get(ref)
    return ambient !== undefined && ambient.value.length > 0 ? ambient.value : undefined
  }
  return {
    ...(config.secretId === undefined || config.secretId.length === 0 ? {} : { secretId: config.secretId }),
    ...(config.secretKey === undefined || config.secretKey.length === 0 ? {} : { secretKey: config.secretKey }),
    resolveCredentials: async () => ({
      secretId: await readOne(secretIdEnv),
      secretKey: await readOne(secretKeyEnv)
    }),
    secretIdEnv,
    secretKeyEnv,
    host: config.host ?? DEFAULT_HOST,
    action: config.action ?? DEFAULT_ACTION,
    apiVersion: config.apiVersion ?? DEFAULT_API_VERSION,
    service: DEFAULT_SERVICE,
    mode: config.mode ?? 0,
    count: config.count ?? 10
  }
}

/**
 * Register the Tencent search provider with `ctx.web`.
 * @param {object} ctx - the plugin context.
 * @param {object} config - this row's config.
 */
function apply(ctx, config) {
  let current = () => config
  installSettingsSection(ctx, WEB_SEARCH_TENCENT_SETTINGS_NAMESPACE, Config, config, {
    setSource: (source) => {
      current = source
    },
    onChange: () => {}
  })
  ctx.web.registerSearchProvider(new TencentSearchProvider(() => resolveOptions(ctx, current())))
}

export {
  Config,
  TENCENT_PROVIDER_ID,
  TencentSearchProvider,
  WEB_SEARCH_TENCENT_SETTINGS_NAMESPACE,
  apply,
  inject,
  mapSearchProResponse,
  name,
  toSource
}
