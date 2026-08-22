import assert from 'node:assert/strict'
import { test } from 'node:test'

import { authorization } from '../lib/sign.js'

/**
 * The expected value below was produced by an INDEPENDENT implementation of
 * TC3-HMAC-SHA256 (a short Python script following the published steps), not by
 * this code. A signature test that compares an implementation against itself
 * proves only that it is deterministic — and a wrong-but-deterministic signer
 * fails at Tencent, days later, as an opaque AuthFailure.
 *
 * Vector: fixed credentials, fixed timestamp 1755800000 (2025-08-21 UTC), the
 * exact body this plugin sends.
 */
const VECTOR = {
  secretId: 'AKIDEXAMPLE',
  secretKey: 'SECRETKEYEXAMPLE',
  service: 'wsa',
  host: 'wsa.tencentcloudapi.com',
  action: 'SearchPro',
  payload: '{"Query":"测试","Mode":0,"Cnt":10}',
  timestamp: 1755800000
}

const EXPECTED =
  'TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2025-08-21/wsa/tc3_request, ' +
  'SignedHeaders=content-type;host;x-tc-action, ' +
  'Signature=dfbcb11ebee5b8b77ea7cd2ec0bfd9411397a0d96c41a9e6461e50c294ac36a0'

test('matches a signature computed by an independent implementation', () => {
  assert.equal(authorization(VECTOR), EXPECTED)
})

test('the credential scope date comes from the timestamp in UTC, not local time', () => {
  // 1755820740 is 2025-08-21T23:59:00Z — still the 21st in UTC, already the
  // 22nd in UTC+8. Deriving the date from local time is the classic bug and it
  // only misfires for part of the day, in some timezones.
  const header = authorization({ ...VECTOR, timestamp: 1755820740 })
  assert.match(header, /Credential=AKIDEXAMPLE\/2025-08-21\/wsa\/tc3_request/)
})

test('a different body changes the signature', () => {
  // The payload is hashed into the canonical request; if it were not, a signed
  // request could be replayed with different search terms.
  const other = authorization({ ...VECTOR, payload: '{"Query":"别的","Mode":0,"Cnt":10}' })
  assert.notEqual(other, EXPECTED)
})
