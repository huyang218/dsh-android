/**
 * The smallest ZIP reader that can open an OOXML file, built on `node:zlib`.
 *
 * <p><b>Why not a dependency.</b> docx/xlsx/pptx are ZIP containers of XML.
 * Reading them needs exactly two things Node already has: the central
 * directory format, and raw inflate. Pulling in a zip library would add a
 * package to `vendor/seed`'s lockfile — which means regenerating the lock,
 * growing a 258 MB payload that ships inside the apk, and carrying another
 * licence into a public binary. For ~120 lines, none of that is worth it.
 *
 * <p><b>Reads the central directory, never the local headers.</b> A local
 * header may carry zeroed sizes with the real ones in a trailing data
 * descriptor, so sizes read from it cannot be trusted; the central directory
 * is authoritative. This is also what makes a truncated file fail loudly here
 * rather than as garbage XML three layers up.
 *
 * <p>Not supported, deliberately: ZIP64 (a >4 GB office document is not a
 * thing this phone will open), encryption, and compression methods other than
 * store and deflate. Each throws by name rather than returning wrong bytes.
 *
 * @module dsh-plugin-tool-doc/zip
 */

import { inflateRawSync } from 'node:zlib'

/** End of central directory signature. */
const EOCD_SIGNATURE = 0x06054b50
/** Central directory file header signature. */
const CENTRAL_SIGNATURE = 0x02014b50
/** Local file header signature. */
const LOCAL_SIGNATURE = 0x04034b50
/** Fixed size of the end-of-central-directory record, before any comment. */
const EOCD_SIZE = 22
/** The archive comment is 16-bit length-prefixed, so the record starts at most this far back. */
const MAX_COMMENT = 0xffff

/** A ZIP that could not be read; `message` names the reason, never a stack of parse noise. */
class ZipError extends Error {
  /** @param {string} message - what was wrong with the archive. */
  constructor(message) {
    super(message)
    this.name = 'ZipError'
  }
}

/**
 * Locate the end-of-central-directory record.
 * @param {Buffer} buffer - the whole archive.
 * @returns {number} its offset.
 */
function findEndOfCentralDirectory(buffer) {
  const earliest = Math.max(0, buffer.length - EOCD_SIZE - MAX_COMMENT)
  for (let at = buffer.length - EOCD_SIZE; at >= earliest; at--) {
    if (buffer.readUInt32LE(at) === EOCD_SIGNATURE) return at
  }
  throw new ZipError('not a ZIP archive: no end-of-central-directory record')
}

/**
 * Every entry in the archive, by name.
 *
 * <p>Directory entries are dropped: they carry no data and only make callers
 * check for them.
 *
 * @param {Buffer} buffer - the whole archive.
 * @returns {Map<string, {method: number, compressedSize: number, size: number, localHeaderOffset: number}>}
 *   one record per file.
 */
function readEntries(buffer) {
  const eocd = findEndOfCentralDirectory(buffer)
  const count = buffer.readUInt16LE(eocd + 10)
  let at = buffer.readUInt32LE(eocd + 16)
  const entries = new Map()
  for (let index = 0; index < count; index++) {
    if (at + 46 > buffer.length || buffer.readUInt32LE(at) !== CENTRAL_SIGNATURE) {
      throw new ZipError(`corrupt ZIP: central directory entry ${index} is not where the record says`)
    }
    const method = buffer.readUInt16LE(at + 10)
    const compressedSize = buffer.readUInt32LE(at + 20)
    const size = buffer.readUInt32LE(at + 24)
    const nameLength = buffer.readUInt16LE(at + 28)
    const extraLength = buffer.readUInt16LE(at + 30)
    const commentLength = buffer.readUInt16LE(at + 32)
    const localHeaderOffset = buffer.readUInt32LE(at + 42)
    const name = buffer.toString('utf8', at + 46, at + 46 + nameLength)
    if (!name.endsWith('/')) entries.set(name, { method, compressedSize, size, localHeaderOffset })
    at += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

/**
 * The decompressed bytes of one entry.
 * @param {Buffer} buffer - the whole archive.
 * @param {{method: number, compressedSize: number, size: number, localHeaderOffset: number}} entry - from {@link readEntries}.
 * @param {string} name - the entry name, for diagnostics.
 * @returns {Buffer} the entry's contents.
 */
function readEntry(buffer, entry, name) {
  const at = entry.localHeaderOffset
  if (at + 30 > buffer.length || buffer.readUInt32LE(at) !== LOCAL_SIGNATURE) {
    throw new ZipError(`corrupt ZIP: "${name}" does not start with a local file header`)
  }
  // The name and extra lengths here may differ from the central directory's —
  // the extra field routinely does — so they are read again rather than reused.
  const nameLength = buffer.readUInt16LE(at + 26)
  const extraLength = buffer.readUInt16LE(at + 28)
  const start = at + 30 + nameLength + extraLength
  const end = start + entry.compressedSize
  if (end > buffer.length) throw new ZipError(`truncated ZIP: "${name}" runs past the end of the file`)
  const raw = buffer.subarray(start, end)
  if (entry.method === 0) return Buffer.from(raw)
  if (entry.method !== 8) throw new ZipError(`unsupported ZIP compression method ${entry.method} for "${name}"`)
  try {
    return inflateRawSync(raw)
  } catch (error) {
    throw new ZipError(`corrupt ZIP: "${name}" did not inflate: ${String(error)}`)
  }
}

/**
 * Open an archive for reading.
 * @param {Buffer} buffer - the whole archive.
 * @returns {{names: () => string[], has: (name: string) => boolean, text: (name: string) => string}}
 *   an entry lister and a UTF-8 reader. OOXML parts are always UTF-8.
 */
function openZip(buffer) {
  const entries = readEntries(buffer)
  return {
    names: () => [...entries.keys()],
    has: (name) => entries.has(name),
    text: (name) => {
      const entry = entries.get(name)
      if (entry === undefined) throw new ZipError(`ZIP has no entry "${name}"`)
      return readEntry(buffer, entry, name).toString('utf8')
    }
  }
}

export { ZipError, openZip }
