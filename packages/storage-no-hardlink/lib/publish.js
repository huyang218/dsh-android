/**
 * Publishing a new file under a name nobody else holds — without `link(2)`.
 *
 * Android denies the hard link outright. The kernel audit record is the whole
 * story:
 *
 *     avc: denied { link } for comm="libuv-worker"
 *       name="session.jsonl.zstd.061ec4043e4b.tmp"
 *       scontext=u:r:untrusted_app_27:s0
 *       tcontext=u:object_r:app_data_file:s0 tclass=file permissive=0
 *
 * It is SELinux, not a permission: no manifest entry grants it, `targetSdk`
 * does not move it, and it applies to the app's OWN private directory. Every
 * upstream call site that publishes a file this way therefore fails with
 * EACCES on Android and only on Android.
 *
 * What `link(tmp, final)` buys upstream is two properties at once:
 *
 *  1. EXCLUSIVE creation — the publish fails (EEXIST) instead of silently
 *     replacing a file another writer already published under that name.
 *  2. ATOMIC content — readers see either no file or the complete one, never
 *     a partially written prefix.
 *
 * `rename` alone keeps (2) and throws (1) away, which is why it is not a
 * drop-in. Claiming the name first restores both:
 *
 *     open(final, O_CREAT|O_EXCL)   → the name is ours, or EEXIST like link
 *     write tmp, fsync              → complete bytes, off to the side
 *     rename(tmp, final)            → atomic swap over our OWN placeholder
 *
 * The cost is honest and small: the window between the claim and the rename
 * is a zero-byte file at the final path. A reader arriving inside that window
 * sees an empty file where upstream would have shown nothing at all. On a
 * single-process handheld host there is no such reader; on a shared machine
 * this shim would be the wrong trade.
 *
 * @module dsh-plugin-storage-no-hardlink/publish
 */

import { constants } from 'node:fs'
import { chmod, mkdir, open, rename, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

/** Private-to-the-app directory mode, matching the upstream stores. */
const DIR_MODE = 0o700

/** Private-to-the-app file mode, matching the upstream stores. */
const FILE_MODE = 0o600

/**
 * fsync a directory so its entries survive a crash (POSIX only).
 * @param {string} path - the directory to sync.
 * @returns {Promise<void>}
 */
async function syncDirectory(path) {
  const handle = await open(path, constants.O_RDONLY)
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}

/**
 * Create a directory and make it — and every ancestor below `boundary` —
 * durable, the way `dsh-attachment-local` does: a directory entry that is not
 * fsynced into its parent can vanish on crash even though its contents were
 * written.
 * @param {string} path - the directory to create.
 * @param {string} boundary - an ancestor already known to be durable; the walk stops there.
 * @returns {Promise<void>}
 */
async function ensureDurableDirectory(path, boundary) {
  const target = resolve(path)
  const stop = resolve(boundary)
  await mkdir(target, { recursive: true, mode: DIR_MODE })
  await chmod(target, DIR_MODE)
  for (let level = target; level !== stop; ) {
    const parent = dirname(level)
    await syncDirectory(parent)
    if (parent === level) break
    level = parent
  }
}

/**
 * Publish a file at `finalPath` exactly once, with no hard link involved.
 *
 * `writeTemp` is called only after the name has been claimed, and must return
 * the path of a fully written, fsynced temp file on the SAME filesystem. On
 * any failure after the claim, both the temp file and the placeholder are
 * removed, so a failed publish leaves the name free rather than poisoned.
 *
 * @param {string} finalPath - the name to publish under.
 * @param {() => Promise<string>} writeTemp - writes the content, returns its temp path.
 * @returns {Promise<void>}
 * @throws EEXIST when the name is already taken — the same signal `link` gives.
 */
async function publishNewFile(finalPath, writeTemp) {
  const claim = await open(finalPath, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, FILE_MODE)
  await claim.close()
  let tmp
  try {
    tmp = await writeTemp()
    await rename(tmp, finalPath)
  } catch (error) {
    if (tmp !== undefined) await rm(tmp, { force: true })
    await rm(finalPath, { force: true })
    throw error
  }
}

export { DIR_MODE, FILE_MODE, ensureDurableDirectory, publishNewFile, syncDirectory }
