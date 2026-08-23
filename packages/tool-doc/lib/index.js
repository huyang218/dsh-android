/**
 * `read_document`: the tool that lets a shell-less agent open an Office file.
 *
 * <p><b>Why this exists at all.</b> The handheld composition has no shell, so
 * the agent cannot pipe a file through anything; what it has is `tool-fs`,
 * whose `read` is a TEXT reader. Point it at a .docx and it gets the bytes of
 * a ZIP — not an error, just noise the model then tries to interpret. This
 * tool is the missing half of "filesystem + LLM + sessions + attachments" for
 * the files people actually keep on a phone.
 *
 * <p><b>Reads through `ctx.fs`, never `node:fs`.</b> `ctx.fs.resolve` is what
 * applies the session's file fence and turns a model-supplied path into a
 * target; going around it with a direct read would give this tool a wider
 * reach than every other tool in the preset, which is precisely the kind of
 * hole a sandbox is for. It also emits `fs/observed` so the file's version is
 * tracked like any other read.
 *
 * <p><b>Bounded output.</b> A 40 MB spreadsheet is a context-window accident
 * waiting to happen, so extraction is capped twice: on the bytes read
 * (`maxBytes`) and on the characters returned (`maxChars`). Truncation is
 * reported in the result rather than silently trimmed — an agent that does not
 * know it saw half a document will answer as if it saw all of it.
 *
 * @module dsh-plugin-tool-doc
 */

import { basename } from 'node:path'

import z from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'

import { ExtractError, extractDocument } from './extract.js'

/** Cordis plugin name used by loader diagnostics. */
const name = 'tool-doc'

/**
 * Services required.
 *
 * <p>`fs` is the one that matters: without it this row waits forever rather
 * than mounting, which surfaces as `session.create` failing — the same failure
 * mode documented for the rest of the preset.
 */
const inject = ['tools', 'fs', 'systemPrompt']

/** Bytes read from one file. Above this the file is refused, not truncated: half a ZIP is not a document. */
const DEFAULT_MAX_BYTES = 32 * 1024 * 1024
/** Characters returned to the model. Roughly 50k tokens of CJK, which is a large but survivable share of a context. */
const DEFAULT_MAX_CHARS = 200000
/** Cooperative timeout budget; extraction is CPU-bound and local, so this is generous. */
const DEFAULT_TIMEOUT_MS = 30000

const Config = z.object({
  maxBytes: z.number().step(1).min(1).default(DEFAULT_MAX_BYTES),
  maxChars: z.number().step(1).min(1).default(DEFAULT_MAX_CHARS),
  timeoutMs: z.number().step(1).min(1).default(DEFAULT_TIMEOUT_MS)
})

/**
 * Clamp extracted text and say whether anything was dropped.
 * @param {string} text - the extracted text.
 * @param {number} maxChars - the cap.
 * @returns {{text: string, truncated: boolean}} the bounded text.
 */
function clamp(text, maxChars) {
  if (text.length <= maxChars) return { text, truncated: false }
  return { text: text.slice(0, maxChars), truncated: true }
}

/**
 * Register the tool.
 * @param {object} ctx - the plugin context.
 * @param {object} config - this row's config.
 */
function apply(ctx, config) {
  const maxBytes = config.maxBytes ?? DEFAULT_MAX_BYTES
  const maxChars = config.maxChars ?? DEFAULT_MAX_CHARS
  const timeoutMs = config.timeoutMs ?? DEFAULT_TIMEOUT_MS

  ctx.systemPrompt.section({
    name: 'tool:read_document',
    order: 115,
    text:
      'Use read_document to read Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files. ' +
      'The plain read tool cannot: those are ZIP containers and it would return binary noise. ' +
      'read_document returns text only — spreadsheet rows come back tab-separated, one block per sheet — ' +
      'so it cannot answer questions about formatting, formulas or merged cells. PDF is not supported.'
  })

  ctx.tools.register(defineTool({
    name: 'read_document',
    description:
      'Read a Word, Excel or PowerPoint file as text. Accepts .docx, .xlsx and .pptx. ' +
      'Not for plain text, Markdown, CSV, JSON or source files — use read for those. PDF is not supported.',
    parameters: {
      file_path: {
        type: 'string',
        required: true,
        description: 'Path to the .docx, .xlsx or .pptx file.'
      }
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          path: { type: 'string', required: true },
          format: { type: 'string', required: true },
          text: { type: 'string', required: true },
          truncated: { type: 'boolean', required: true }
        }
      },
      render: (_args, value) => [{
        type: 'text',
        text: value.truncated
          ? `${value.text}\n\n[truncated at ${maxChars} characters — ask for a specific part if you need more]`
          : value.text
      }]
    },
    timeoutMs,
    isConcurrencySafe: () => true,
    async execute(args, exec) {
      if (args.file_path.trim().length === 0) throw new Error('file_path must be a non-empty string')
      const target = await ctx.fs.resolve(args.file_path, { signal: exec.signal })
      const info = await ctx.fs.stat(target, exec.signal)
      if (info.size !== undefined && info.size > maxBytes) {
        throw new Error(
          `cannot read "${target.displayPath}": ${info.size} bytes exceeds the ${maxBytes}-byte limit for read_document`
        )
      }
      const data = await ctx.fs.readBytes(target, exec.signal, maxBytes)
      let extracted
      try {
        extracted = extractDocument(Buffer.from(data), target.displayPath)
      } catch (error) {
        // ExtractError messages are written for the model to act on ("read the
        // text version instead"), so they are surfaced as-is rather than
        // wrapped in a stack the model has to guess at.
        if (error instanceof ExtractError) throw new Error(error.message, { cause: error })
        throw error
      }
      const bounded = clamp(extracted.text, maxChars)
      ctx.emit('fs/observed', target, { kind: 'present', version: info.version }, exec)
      return {
        path: target.displayPath,
        format: extracted.format,
        text: bounded.text,
        truncated: bounded.truncated
      }
    },
    presentCall(args) {
      return {
        card: 'generic',
        title: `Read document ${basename(args.file_path)}`,
        kind: 'read',
        locations: [{ path: args.file_path }]
      }
    }
  }))
}

export {
  Config,
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_CHARS,
  DEFAULT_TIMEOUT_MS,
  apply,
  clamp,
  inject,
  name
}
