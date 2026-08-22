/**
 * Turning a `SearchPro` body into the seam's result shape.
 *
 * <p>Split from the plugin because this is the only part with behaviour worth
 * testing, and the plugin itself cannot even be imported outside a dsh install
 * — it resolves `@deepseek-ai/dsh-web` and four siblings. This file imports
 * nothing, so the tests run against the shipped code rather than a copy.
 *
 * @module dsh-plugin-web-search-tencent/map
 */

/** A provider-side failure; the plugin re-wraps it as the seam's WebError. */
class TencentSearchError extends Error {
  constructor(message, options) {
    super(message, options)
    this.name = 'TencentSearchError'
  }
}

/**
 * One page from `Pages`, already parsed, projected onto the seam's source shape.
 * @param {object} page - parsed page object.
 * @returns {object | undefined} a source, or undefined when it carries no url.
 */
function toSource(page) {
  const url = typeof page?.url === 'string' ? page.url : ''
  if (url.length === 0) return undefined
  // `passage` is the search-time snippet; `content` is the fuller extraction.
  // Prefer the snippet: it is what the ranking was computed against, and the
  // full text belongs to a fetch, which this provider does not do.
  const snippet = [page.passage, page.content].find((v) => typeof v === 'string' && v.length > 0)
  const publishedAt = typeof page.date === 'string' && page.date.length > 0 ? page.date : undefined
  const title = typeof page.title === 'string' && page.title.length > 0 ? page.title : undefined
  return {
    url,
    ...(title === undefined ? {} : { title }),
    ...(snippet === undefined ? {} : { snippet }),
    ...(publishedAt === undefined ? {} : { publishedAt })
  }
}

/**
 * Map a raw `SearchPro` response body onto the seam's result.
 * @param {object} body - the parsed HTTP body, including its `Response` envelope.
 * @param {number} requested - how many results were asked for, to report truncation.
 * @returns {{sources: object[], truncated: boolean}} the seam's result.
 */
function mapSearchProResponse(body, requested) {
  const envelope = body?.Response ?? {}
  // Tencent reports failures INSIDE a 200: an `Error` member on the envelope.
  // Treating HTTP status as the verdict would turn those into empty results.
  if (envelope.Error !== undefined) {
    const code = envelope.Error.Code ?? 'unknown'
    const message = envelope.Error.Message ?? 'unknown error'
    throw new TencentSearchError(`Tencent search error (${code}): ${message}`)
  }
  const pages = Array.isArray(envelope.Pages) ? envelope.Pages : []
  const seen = new Set()
  const sources = []
  for (const raw of pages) {
    let parsed
    try {
      parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    } catch {
      // One malformed row must not cost every other result.
      continue
    }
    const source = toSource(parsed)
    if (source === undefined || seen.has(source.url)) continue
    seen.add(source.url)
    sources.push(source)
  }
  return { sources, truncated: sources.length >= requested }
}

export { TencentSearchError, mapSearchProResponse, toSource }
