/**
 * A ZIP writer, used only by the tests.
 *
 * <p>The tests build real containers instead of committing binary fixtures:
 * a fixture would be opaque in review, and a test that reads a file someone
 * generated once proves less than one whose input is spelled out in the test
 * body. It also exercises the reader against both stored and deflated entries,
 * which a single fixture would not.
 *
 * <p>This is not a general ZIP writer. It writes what a reader must handle —
 * central directory, local headers, both compression methods — and nothing
 * else.
 */

import { deflateRawSync } from 'node:zlib'

/**
 * Build an archive.
 * @param {Array<{name: string, data: string | Buffer, store?: boolean}>} files - entries in order.
 * @returns {Buffer} the archive.
 */
export function zip(files) {
  const locals = []
  const central = []
  let offset = 0

  for (const file of files) {
    const name = Buffer.from(file.name, 'utf8')
    const raw = Buffer.isBuffer(file.data) ? file.data : Buffer.from(file.data, 'utf8')
    const stored = file.store === true
    const body = stored ? raw : deflateRawSync(raw)

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(stored ? 0 : 8, 8)
    local.writeUInt32LE(0, 14) // crc32: readers here never check it
    local.writeUInt32LE(body.length, 18)
    local.writeUInt32LE(raw.length, 22)
    local.writeUInt16LE(name.length, 26)
    locals.push(local, name, body)

    const entry = Buffer.alloc(46)
    entry.writeUInt32LE(0x02014b50, 0)
    entry.writeUInt16LE(20, 6)
    entry.writeUInt16LE(stored ? 0 : 8, 10)
    entry.writeUInt32LE(0, 16)
    entry.writeUInt32LE(body.length, 20)
    entry.writeUInt32LE(raw.length, 24)
    entry.writeUInt16LE(name.length, 28)
    entry.writeUInt32LE(offset, 42)
    central.push(entry, name)

    offset += 30 + name.length + body.length
  }

  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(files.length, 8)
  end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)

  return Buffer.concat([...locals, directory, end])
}

/** A .docx whose `word/document.xml` body is the given markup. */
export function docx(body) {
  return zip([
    { name: '[Content_Types].xml', data: '<Types/>' },
    { name: 'word/document.xml', data: `<?xml version="1.0"?><w:document><w:body>${body}</w:body></w:document>` }
  ])
}
