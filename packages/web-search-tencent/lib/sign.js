/**
 * Tencent Cloud's TC3-HMAC-SHA256 request signature.
 *
 * <p>Separate from the provider because it is the only part with an
 * externally-defined right answer: the algorithm is published, so the test can
 * check this against a vector computed independently rather than against
 * itself. Everything else in this package is glue.
 *
 * <p>Reference: https://cloud.tencent.com/document/product/213/30654
 *
 * @module dsh-plugin-web-search-tencent/sign
 */

import { createHash, createHmac } from 'node:crypto'

/** The only algorithm this signer speaks; it also appears verbatim in the header. */
const ALGORITHM = 'TC3-HMAC-SHA256'

/** Terminator of the credential scope, fixed by the spec. */
const REQUEST_TERMINATOR = 'tc3_request'

/**
 * Lowercase hex SHA-256, the encoding every hashed field in the spec uses.
 * @param {string} value - text to hash.
 * @returns {string} hex digest.
 */
function sha256Hex(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

/**
 * HMAC-SHA256 with a binary key. The derived keys are binary on purpose — hex
 * or base64 in between would silently produce a different (wrong) signature.
 * @param {Buffer | string} key - the key, binary after the first step.
 * @param {string} data - the message.
 * @returns {Buffer} raw digest.
 */
function hmac(key, data) {
  return createHmac('sha256', key).update(data, 'utf8').digest()
}

/**
 * The `Authorization` header for one Tencent Cloud API v3 call.
 *
 * <p>Only the POST + `application/json` shape is implemented, which is the only
 * shape this plugin makes: the canonical URI is always `/` and the canonical
 * query string is always empty, so the two fields most implementations get
 * wrong cannot vary here.
 *
 * <p>The timestamp is a parameter rather than read from the clock so the
 * signature is a pure function of its inputs — that is what makes it testable,
 * and the caller is the one that has to answer for clock skew (Tencent rejects
 * anything more than five minutes off).
 *
 * @param {object} input - signing inputs.
 * @param {string} input.secretId - the account's SecretId.
 * @param {string} input.secretKey - the account's SecretKey.
 * @param {string} input.service - product short name, e.g. `wsa`.
 * @param {string} input.host - request host, e.g. `wsa.tencentcloudapi.com`.
 * @param {string} input.action - the API action, e.g. `SearchPro`.
 * @param {string} input.payload - the exact JSON body that will be sent.
 * @param {number} input.timestamp - UNIX seconds, also sent as `X-TC-Timestamp`.
 * @returns {string} the complete Authorization header value.
 */
function authorization({ secretId, secretKey, service, host, action, payload, timestamp }) {
  // The date must come from the timestamp in UTC — deriving it from local time
  // is the classic failure that only shows up for people west of Greenwich.
  const date = new Date(timestamp * 1000).toISOString().slice(0, 10)

  const contentType = 'application/json; charset=utf-8'
  // Header names lowercased and sorted; the action goes in lowercase too.
  const canonicalHeaders =
    `content-type:${contentType}\n` +
    `host:${host}\n` +
    `x-tc-action:${action.toLowerCase()}\n`
  const signedHeaders = 'content-type;host;x-tc-action'

  const canonicalRequest = [
    'POST',
    '/',
    '',
    canonicalHeaders,
    signedHeaders,
    sha256Hex(payload)
  ].join('\n')

  const credentialScope = `${date}/${service}/${REQUEST_TERMINATOR}`
  const stringToSign = [
    ALGORITHM,
    String(timestamp),
    credentialScope,
    sha256Hex(canonicalRequest)
  ].join('\n')

  const secretDate = hmac(`TC3${secretKey}`, date)
  const secretService = hmac(secretDate, service)
  const secretSigning = hmac(secretService, REQUEST_TERMINATOR)
  const signature = createHmac('sha256', secretSigning).update(stringToSign, 'utf8').digest('hex')

  return `${ALGORITHM} Credential=${secretId}/${credentialScope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`
}

export { ALGORITHM, authorization, sha256Hex }
