/**
 * Text out of the three Office XML containers, without an XML parser.
 *
 * <p><b>Why no XML parser.</b> Extraction here is not "parse the document
 * model" — it is "concatenate the text runs in document order, and put breaks
 * where the format says a break is". For that, the tag stream matters and the
 * tree does not. A regex walk over the tag stream is a few dozen lines and has
 * no dependency; a DOM would cost a package in the seed and would still need
 * every one of the format-specific rules below.
 *
 * <p>What that costs: this cannot answer structural questions (which cell is
 * merged, which run is bold). It is not a converter. It is the "what does this
 * file say" step an agent needs before it can reason about a document.
 *
 * @module dsh-plugin-tool-doc/ooxml
 */

/** XML entities that appear in OOXML text runs. `&amp;` is unescaped last, or `&amp;lt;` would become `<`. */
function unescapeXml(text) {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&amp;/g, '&')
}

/**
 * The text of every `<tag>…</tag>` in document order.
 * @param {string} xml - the part's XML.
 * @param {string} tag - a qualified tag name, e.g. `w:t`.
 * @returns {string[]} the runs, unescaped.
 */
function runsOf(xml, tag) {
  const pattern = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, 'g')
  return [...xml.matchAll(pattern)].map((match) => unescapeXml(match[1]))
}

/**
 * Paragraph text out of `word/document.xml`.
 *
 * <p>Breaks come from the markup, not from guessing: `</w:p>` ends a
 * paragraph, `<w:br/>` and `<w:cr/>` are hard breaks, `<w:tab/>` is a tab.
 * Without this the whole document arrives as one line and a table is
 * unreadable.
 *
 * @param {string} xml - the contents of `word/document.xml`.
 * @returns {string} the document's text.
 */
function docxText(xml) {
  const lines = []
  for (const [paragraph] of xml.matchAll(/<w:p(?:\s[^>]*)?>[\s\S]*?<\/w:p>/g)) {
    // Tabs and breaks live OUTSIDE <w:t>, so they are turned into literals
    // first and then picked up by the same ordered walk as the text runs.
    // Doing it the other way round loses them, and a table becomes one line.
    const marked = paragraph
      .replace(/<w:tab\/>/g, '\t')
      .replace(/<w:(?:br|cr)\/>/g, '\n')
    const pieces = []
    for (const match of marked.matchAll(/<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>|([\t\n])/g)) {
      pieces.push(match[1] === undefined ? match[2] : unescapeXml(match[1]))
    }
    lines.push(pieces.join(''))
  }
  return lines.join('\n')
}

/**
 * Sheet contents out of an xlsx, as TSV per sheet.
 *
 * <p>Most cell text does not live in the sheet: `t="s"` means the value is an
 * index into `xl/sharedStrings.xml`. A reader that ignores the shared string
 * table gets a spreadsheet full of integers, which looks like data and is not.
 *
 * @param {{ shared: string[], sheets: Array<{name: string, xml: string}> }} book - the parts.
 * @returns {string} one `## sheet` block per sheet, rows as tab-separated cells.
 */
function xlsxText(book) {
  const blocks = []
  for (const sheet of book.sheets) {
    const rows = []
    for (const [row] of sheet.xml.matchAll(/<row\b[^>]*?(?:\/>|>[\s\S]*?<\/row>)/g)) {
      const cells = []
      // The `/>` alternative is not tidiness. An empty cell is written
      // `<c r="B2"/>`, and a pattern that only knows `<c …>…</c>` does not
      // just skip it: it matches `<c/>` as an open tag and runs on to the NEXT
      // cell's `</c>`, swallowing it. Two columns become one, the row still
      // looks like data, and every column after the blank is shifted.
      for (const match of row.matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const attributes = match[1]
        const body = match[2] ?? ''
        const type = /\bt="([^"]+)"/.exec(attributes)?.[1]
        if (type === 'inlineStr') {
          cells.push(runsOf(body, 't').join(''))
          continue
        }
        const value = runsOf(body, 'v')[0]
        if (value === undefined) {
          cells.push('')
          continue
        }
        cells.push(type === 's' ? (book.shared[Number(value)] ?? '') : value)
      }
      rows.push(cells.join('\t'))
    }
    blocks.push(`## ${sheet.name}\n${rows.join('\n')}`)
  }
  return blocks.join('\n\n')
}

/**
 * Slide text out of a pptx.
 * @param {Array<{name: string, xml: string}>} slides - the slide parts, in order.
 * @returns {string} one `## slide` block per slide.
 */
function pptxText(slides) {
  return slides
    .map((slide) => `## ${slide.name}\n${runsOf(slide.xml, 'a:t').join('\n')}`)
    .join('\n\n')
}

export { docxText, pptxText, runsOf, unescapeXml, xlsxText }
