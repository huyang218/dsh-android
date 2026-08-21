/**
 * The JSONL session persistence backend, with the one Android-impossible
 * syscall taken out.
 *
 * Upstream `materializePosix` publishes a new session log with
 * `link(tmp, final)` (rc.7 `lib/index.js:1128`). Android's SELinux denies
 * `link` to app domains — see {@link module:dsh-plugin-storage-no-hardlink/publish}
 * for the audit record — so on a phone EVERY session fails at its first turn
 * with `EACCES: permission denied, link ...`, and the UI shows only
 * "This turn failed".
 *
 * This subclass overrides that ONE method and inherits everything else. It is
 * deliberately not a fork: the encoder, the coordinator, the append path, the
 * torn-tail recovery and the artifact format all stay upstream's, so a runtime
 * bump moves them without touching this file. The only thing that can rot here
 * is the shape of `materializePosix` itself — if upstream changes the durable
 * publish sequence, this override has to follow.
 *
 * @module dsh-plugin-storage-no-hardlink/session-persistence
 */

import { mkdir } from 'node:fs/promises'
import { dirname } from 'node:path'

import JsonlSessionPersistence from '@deepseek-ai/dsh-session-persistence-jsonl'

import { DIR_MODE, publishNewFile } from './publish.js'

/**
 * JSONL persistence whose durable publish uses claim + rename instead of a
 * hard link. Registers as `ctx.sessionPersistence`, same as upstream.
 */
class NoHardlinkJsonlSessionPersistence extends JsonlSessionPersistence {
  /**
   * Materialize a new log: same directory-durability ladder as upstream, same
   * pre-flight rejection, same temp file — only the publish differs.
   * @param {string} project - the project directory holding this session.
   * @param {string} dir - the session's own directory.
   * @param {string} finalPath - the log's published path.
   * @param {string} id - the session id, for the rejection message.
   * @param {string | Buffer} content - the encoded header line plus first batch.
   * @returns {Promise<void>}
   */
  async materializePosix(project, dir, finalPath, id, content) {
    await mkdir(this.root, { recursive: true, mode: DIR_MODE })
    await this.syncDirPosix(dirname(this.root))
    await mkdir(project, { recursive: true, mode: DIR_MODE })
    await this.syncDirPosix(this.root)
    await mkdir(dir, { recursive: true, mode: DIR_MODE })
    await this.syncDirPosix(project)
    await this.rejectExistingLog(finalPath, id)
    try {
      await publishNewFile(finalPath, () => this.writeSyncedTempFile(finalPath, content))
    } catch (error) {
      // The claim losing to an existing file is upstream's EEXIST case, and it
      // means the same thing here: someone published this log first.
      if (error?.code === 'EEXIST') {
        throw new Error(`refusing to materialize ${JSON.stringify(id)}: a log already exists on disk (load/resume it instead)`)
      }
      throw error
    }
    await this.syncDirPosix(dir)
  }
}

export { NoHardlinkJsonlSessionPersistence, NoHardlinkJsonlSessionPersistence as default }
