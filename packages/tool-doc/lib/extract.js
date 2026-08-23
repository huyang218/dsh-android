/**
 * One entry point: bytes in, text out, format decided by the bytes.
 *
 * <p><b>The extension is a hint, never the answer.</b> A file named `.docx`
 * that is actually a PDF is common enough (browsers rename downloads, people
 * rename exports), and trusting the name turns that into a wall of mojibake
 * the model then tries to reason about. Every format here is identified by its
 * magic bytes; the extension only picks between the three OOXML flavours,
 * which share one container and are told apart by which parts are inside.
 *
 * <p><b>What is deliberately not here: PDF.</b> Extracting text from a PDF is
 * not a format-sniffing problem — it needs an xref walk, stream inflation,
 * content-stream tokenizing, and per-font ToUnicode CMaps, and getting the
 * last one wrong yields plausible-looking wrong characters rather than an
 * error. That is a decision with a real cost either way (hand-rolled and
 * imperfect, or `pdfjs-dist` at ~10 MB inside a payload that already ships
 * 258 MB in the apk), so it is left out until the decision is made rather
 * than half-done. `read_document` refuses a PDF by name and says so.
 *
 * @module dsh-plugin-tool-doc/extract
 */

import { openZip } from './zip.js'
import { docxText, pptxText, runsOf, xlsxText } from './ooxml.js'

/** A file this tool cannot turn into text; the message is written for the model to act on. */
class ExtractError extends Error {
  /**
   * @param {string} message - what is wrong and, where there is one, what to do instead.
   * @param {string} code - a stable machine-readable reason.
   */
  constructor(message, code) {
    super(message)
    this.name = 'ExtractError'
    this.code = code
  }
}

/** Local-file-header magic; every ZIP (and therefore every OOXML file) starts with it. */
const ZIP_MAGIC = Buffer.from('PK', 'latin1')
const PDF_MAGIC = Buffer.from('%PDF-', 'latin1')

/** Sheet parts sort by their trailing number, so sheet10 does not land between sheet1 and sheet2. */
function byPartNumber(left, right) {
  const number = (name) => Number(/(\d+)\.xml$/.exec(name)?.[1] ?? 0)
  return number(left) - number(right) || left.localeCompare(right)
}

/**
 * Sheet names as the person sees them, from the workbook part.
 * @param {ReturnType<typeof openZip>} zip - the opened container.
 * @returns {string[]} display names in workbook order, empty when the part is absent.
 */
function sheetNames(zip) {
  if (!zip.has('xl/workbook.xml')) return []
  const xml = zip.text('xl/workbook.xml')
  return [...xml.matchAll(/<sheet\b[^>]*\bname="([^"]*)"/g)].map((match) => match[1])
}

/**
 * Text of an OOXML container.
 * @param {ReturnType<typeof openZip>} zip - the opened container.
 * @returns {string} the extracted text.
 */
function ooxmlText(zip) {
  if (zip.has('word/document.xml')) return docxText(zip.text('word/document.xml'))

  if (zip.has('xl/workbook.xml')) {
    // The shared string table is indexed by position, so the array must keep
    // empty entries — filtering them would shift every later index and quietly
    // put the wrong label on every cell after the first blank one.
    const shared = zip.has('xl/sharedStrings.xml')
      ? [...zip.text('xl/sharedStrings.xml').matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)]
          .map((match) => runsOf(match[1], 't').join(''))
      : []
    const parts = zip.names().filter((name) => /^xl\/worksheets\/sheet\d*\.xml$/.test(name)).sort(byPartNumber)
    const names = sheetNames(zip)
    return xlsxText({
      shared,
      sheets: parts.map((name, index) => ({ name: names[index] ?? name, xml: zip.text(name) }))
    })
  }

  const slides = zip.names().filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name)).sort(byPartNumber)
  if (slides.length > 0) {
    return pptxText(slides.map((name, index) => ({ name: `Slide ${index + 1}`, xml: zip.text(name) })))
  }

  throw new ExtractError(
    'this ZIP is not a Word, Excel or PowerPoint file: it has no word/document.xml, xl/workbook.xml or ppt/slides/',
    'UNSUPPORTED_ZIP'
  )
}

/**
 * Turn a document's bytes into text.
 *
 * @param {Buffer} data - the file's bytes.
 * @param {string} displayPath - the path to name in errors.
 * @returns {{format: string, text: string}} the detected format and its text.
 * @throws {ExtractError} when the bytes are a format this tool does not read.
 */
function extractDocument(data, displayPath) {
  if (data.subarray(0, PDF_MAGIC.length).equals(PDF_MAGIC)) {
    throw new ExtractError(
      `cannot read "${displayPath}": PDF is not supported by read_document yet. ` +
        'If the file has a text or Office version, read that instead.',
      'PDF_UNSUPPORTED'
    )
  }
  if (!data.subarray(0, ZIP_MAGIC.length).equals(ZIP_MAGIC)) {
    throw new ExtractError(
      `cannot read "${displayPath}": read_document handles .docx/.xlsx/.pptx. ` +
        'Plain text, Markdown, CSV, JSON and source files are what the read tool is for.',
      'NOT_A_DOCUMENT'
    )
  }
  const zip = openZip(data)
  const text = ooxmlText(zip)
  const format = zip.has('word/document.xml') ? 'docx' : zip.has('xl/workbook.xml') ? 'xlsx' : 'pptx'
  return { format, text }
}

export { ExtractError, extractDocument, ooxmlText, sheetNames }
