/**
 * The local attachment store, with the same one syscall taken out.
 *
 * `dsh-attachment-local` is content-addressed: an image's bytes are published
 * at `objects/<xx>/<sha256>` with `link(temporary, target)` (rc.7
 * `lib/index.js:192`), which Android denies exactly as it denies the session
 * log's publish. Nothing surfaces until someone attaches an image — and then
 * it is an `ATTACHMENT_WRITE_FAILED` with the EACCES buried in `cause`.
 *
 * Unlike the session log, upstream's save path is a module-level function, not
 * a method, so the override has to restate it. That is a real maintenance cost
 * and the reason this file is longer than its sibling: the admission checks,
 * the object layout and the returned reference are re-stated here and can
 * drift from upstream. What is NOT restated is the interesting part —
 * `detectImage` (the decoder and the pixel-limit policy) and the whole read
 * path stay upstream's.
 *
 * Content addressing makes the exclusivity question easier than it looks: two
 * writers racing for one name are, by construction, writing identical bytes.
 * The claim + rename shim still reports EEXIST, and EEXIST is handled the way
 * upstream handles it — verify what is already there, then keep it.
 *
 * @module dsh-plugin-storage-no-hardlink/attachment
 */

import { createHash, randomUUID } from 'node:crypto'
import { constants } from 'node:fs'
import { open, readFile, rm } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'

import { AttachmentError, AttachmentId } from '@deepseek-ai/dsh-attachment'
import LocalAttachmentStore, { detectImage } from '@deepseek-ai/dsh-attachment-local'

import { FILE_MODE, ensureDurableDirectory, publishNewFile, syncDirectory } from './publish.js'

/**
 * Hex sha256 of the encoded bytes — the object's name and its integrity check.
 * @param {Uint8Array} data - encoded image bytes.
 * @returns {string} lowercase hex digest.
 */
function digest(data) {
  return createHash('sha256').update(data).digest('hex')
}

/**
 * The upstream display-name policy: basename only, control characters
 * stripped, 255 chars, empty means absent.
 * @param {string | undefined} value - the client-supplied filename.
 * @returns {string | undefined} the name to record, if any.
 */
function displayName(value) {
  if (value === undefined) return undefined
  const clean = value
    .slice(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1)
    .replace(/[\u0000-\u001F\u007F]/g, '')
    .trim()
    .slice(0, 255)
  return clean === '' ? undefined : clean
}

/**
 * Where one object lives under the versioned root.
 * @param {string} root - absolute `DSH_HOME/attachments/v1`.
 * @param {string} sha256 - hex digest.
 * @returns {string} the object's absolute path.
 */
function objectPath(root, sha256) {
  return join(root, 'objects', sha256.slice(0, 2), sha256)
}

/**
 * Admission policy for one image, upstream's `inspectMetadata`: the bytes
 * exist, the raster decodes within the pixel limit, and the declared media
 * type is the truth.
 * @param {Uint8Array} data - encoded image bytes.
 * @param {string} declaredMediaType - the media type the client claims.
 * @param {number} maxPixels - intrinsic pixel ceiling.
 * @returns {Promise<object>} detected metadata plus the encoded byte count.
 */
async function inspectMetadata(data, declaredMediaType, maxPixels) {
  if (data.byteLength === 0) throw new AttachmentError('Image is empty.', 'INVALID_IMAGE')
  const detected = await detectImage(data, maxPixels)
  if (detected.mediaType !== declaredMediaType) {
    throw new AttachmentError('Declared image type does not match its bytes.', 'IMAGE_TYPE_MISMATCH')
  }
  return { ...detected, bytes: data.byteLength }
}

/**
 * Content-addressed attachment store whose publish uses claim + rename.
 * Registers as `ctx.attachmentStore`, same as upstream.
 */
class NoHardlinkLocalAttachmentStore extends LocalAttachmentStore {
  /**
   * Save one image and return the reference the session log records.
   * @param {{data: Uint8Array, mediaType: string, name?: string}} input - encoded bytes and declared metadata.
   * @returns {Promise<object>} the durable content-addressed reference.
   */
  async saveImage(input) {
    const limits = this.imageLimits
    if (input.data.byteLength > limits.maxImageBytes) {
      throw new AttachmentError('Image exceeds the configured byte limit.', 'IMAGE_TOO_LARGE')
    }
    const metadata = await inspectMetadata(input.data, input.mediaType, limits.maxImagePixels)
    const sha256 = digest(input.data)
    const root = this.root
    const objects = join(root, 'objects')
    const bucket = join(objects, sha256.slice(0, 2))
    const staging = join(root, 'tmp')
    // DSH_HOME: the root is `<home>/attachments/v1`, and the home is dsh's own
    // to create — the durability walk stops there rather than at the
    // filesystem root, which is the one place this is thinner than upstream.
    const boundary = dirname(dirname(resolve(root)))
    const target = objectPath(root, sha256)
    const temporary = join(staging, randomUUID())

    try {
      await ensureDurableDirectory(bucket, boundary)
      await ensureDurableDirectory(staging, boundary)
      try {
        await publishNewFile(target, async () => {
          const handle = await open(temporary, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, FILE_MODE)
          try {
            await handle.writeFile(input.data)
            await handle.sync()
          } finally {
            await handle.close()
          }
          return temporary
        })
      } catch (error) {
        if (error?.code !== 'EEXIST') throw error
        // Already stored. Upstream verifies rather than trusts the name, and
        // so do we: a corrupt object under a content address is the one thing
        // content addressing must never paper over.
        if (digest(new Uint8Array(await readFile(target))) !== sha256) {
          throw new AttachmentError('Stored attachment failed integrity verification.', 'ATTACHMENT_CORRUPT')
        }
      }
      await syncDirectory(bucket)
      await syncDirectory(objects)
    } catch (error) {
      await rm(temporary, { force: true })
      if (error instanceof AttachmentError) throw error
      throw new AttachmentError('Unable to persist image attachment.', 'ATTACHMENT_WRITE_FAILED', { cause: error })
    }
    await rm(temporary, { force: true })

    const name = displayName(input.name)
    return {
      attachmentId: AttachmentId(`sha256:${sha256}`),
      ...metadata,
      ...(name !== undefined ? { name } : {})
    }
  }
}

export { NoHardlinkLocalAttachmentStore, NoHardlinkLocalAttachmentStore as default }
