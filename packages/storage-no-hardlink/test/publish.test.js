import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { before, describe, test } from 'node:test'

import { publishNewFile } from '../lib/publish.js'

/**
 * The point of these tests is the property the shim exists to preserve. Any
 * rename-based publish passes "the bytes land"; only a correct one also
 * refuses the second writer and leaves no name behind on failure.
 */
describe('publishNewFile', () => {
  let dir

  before(async () => {
    dir = await mkdtemp(join(tmpdir(), 'dsh-nohardlink-'))
  })

  test('publishes the written bytes and consumes the temp file', async () => {
    const finalPath = join(dir, 'published')
    await publishNewFile(finalPath, async () => {
      const tmp = `${finalPath}.tmp`
      await writeFile(tmp, 'hello')
      return tmp
    })
    assert.equal(await readFile(finalPath, 'utf8'), 'hello')
    const left = (await readdir(dir)).filter((name) => name.endsWith('.tmp'))
    assert.deepEqual(left, [])
  })

  test('refuses a name that is already taken, the way link does', async () => {
    const finalPath = join(dir, 'taken')
    await writeFile(finalPath, 'first writer')
    await assert.rejects(
      publishNewFile(finalPath, async () => {
        const tmp = `${finalPath}.tmp`
        await writeFile(tmp, 'second writer')
        return tmp
      }),
      (error) => error.code === 'EEXIST'
    )
    // The loser must not have touched the winner's bytes.
    assert.equal(await readFile(finalPath, 'utf8'), 'first writer')
  })

  test('leaves the name free when the write fails', async () => {
    const finalPath = join(dir, 'failed')
    await assert.rejects(
      publishNewFile(finalPath, async () => {
        throw new Error('encoder blew up')
      }),
      /encoder blew up/
    )
    // A leftover placeholder would poison the name: the next attempt would see
    // EEXIST and report "a log already exists on disk" for a log that never was.
    await assert.rejects(readFile(finalPath), (error) => error.code === 'ENOENT')
  })
})
