import assert from 'node:assert/strict'
import { test } from 'node:test'

import { TencentSearchError, mapSearchProResponse, toSource } from '../lib/map.js'

/** Build a body in the shape SearchPro actually returns: Pages are JSON STRINGS. */
function body(pages, extra = {}) {
  return { Response: { Query: 'q', Pages: pages.map((p) => (typeof p === 'string' ? p : JSON.stringify(p))), ...extra } }
}

test('parses the JSON-string pages the API returns', () => {
  const result = mapSearchProResponse(body([
    { title: '标题一', url: 'https://a.example/1', passage: '摘要一', date: '2025/02/26 09:37:00' },
    { title: '标题二', url: 'https://b.example/2', content: '正文二' }
  ]), 10)
  assert.deepEqual(result.sources, [
    { url: 'https://a.example/1', title: '标题一', snippet: '摘要一', publishedAt: '2025/02/26 09:37:00' },
    { url: 'https://b.example/2', title: '标题二', snippet: '正文二' }
  ])
  assert.equal(result.truncated, false)
})

test('prefers passage over content', () => {
  // `passage` is the snippet the ranking was computed against; `content` is the
  // fuller extraction and belongs to a fetch, which this provider does not do.
  const source = toSource({ url: 'https://x.example', passage: '短', content: '长长长' })
  assert.equal(source.snippet, '短')
})

test('skips a malformed page instead of failing the whole search', () => {
  const result = mapSearchProResponse(body(['{not json', { url: 'https://ok.example' }]), 10)
  assert.deepEqual(result.sources.map((s) => s.url), ['https://ok.example'])
})

test('drops duplicates and pages with no url', () => {
  const result = mapSearchProResponse(body([
    { url: 'https://dup.example', title: '一' },
    { url: 'https://dup.example', title: '二' },
    { title: '没有 url' }
  ]), 10)
  assert.deepEqual(result.sources.map((s) => s.title), ['一'])
})

test('reports truncation when the API filled the requested count', () => {
  const pages = Array.from({ length: 3 }, (_, i) => ({ url: `https://n.example/${i}` }))
  assert.equal(mapSearchProResponse(body(pages), 3).truncated, true)
  assert.equal(mapSearchProResponse(body(pages), 10).truncated, false)
})

test('an Error member inside a 200 is a failure, not an empty result', () => {
  // Tencent reports failures inside a 200 body. Reading only the HTTP status
  // would turn an auth failure into "no results found", which is the worst
  // possible way to be wrong: the agent would report the web as empty.
  assert.throws(
    () => mapSearchProResponse({ Response: { Error: { Code: 'AuthFailure', Message: '签名错误' } } }, 10),
    (error) => error instanceof TencentSearchError && /AuthFailure/.test(error.message)
  )
})
