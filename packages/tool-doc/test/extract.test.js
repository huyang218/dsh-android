import assert from 'node:assert/strict'
import { test } from 'node:test'

import { ExtractError, extractDocument } from '../lib/extract.js'
import { ZipError, openZip } from '../lib/zip.js'
import { docx, zip } from './build.js'

test('reads a stored entry and a deflated one from the same archive', () => {
  // Both methods appear in real Office files: tiny parts are often stored.
  const archive = openZip(zip([
    { name: 'a.txt', data: 'stored', store: true },
    { name: 'b.txt', data: 'deflated' }
  ]))
  assert.equal(archive.text('a.txt'), 'stored')
  assert.equal(archive.text('b.txt'), 'deflated')
})

test('directory entries are not listed as files', () => {
  const archive = openZip(zip([{ name: 'word/', data: '' }, { name: 'word/document.xml', data: '<x/>' }]))
  assert.deepEqual(archive.names(), ['word/document.xml'])
})

test('a truncated archive fails as a ZipError, not as garbage text', () => {
  const whole = zip([{ name: 'a.txt', data: 'hello' }])
  assert.throws(() => openZip(whole.subarray(0, 10)), (error) => error instanceof ZipError)
})

test('docx: paragraphs become lines, tabs and breaks survive', () => {
  // Tabs and breaks are siblings of <w:t>, not children. Extracting only the
  // <w:t> runs loses them and a two-column table collapses into one line.
  const file = docx(
    '<w:p><w:r><w:t>第一段</w:t></w:r></w:p>' +
    '<w:p><w:r><w:t>左</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>右</w:t></w:r></w:p>' +
    '<w:p><w:r><w:t>上</w:t><w:br/><w:t>下</w:t></w:r></w:p>'
  )
  const result = extractDocument(file, 'a.docx')
  assert.equal(result.format, 'docx')
  assert.equal(result.text, '第一段\n左\t右\n上\n下')
})

test('docx: a run split across elements is joined without a space', () => {
  // Word splits a word at every formatting boundary and at spell-check marks.
  // Joining with anything but the empty string invents whitespace that is not
  // in the document.
  const file = docx('<w:p><w:r><w:t>Hel</w:t></w:r><w:r><w:t xml:space="preserve">lo </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>')
  assert.equal(extractDocument(file, 'a.docx').text, 'Hello world')
})

test('docx: XML entities are unescaped, and &amp; last', () => {
  // Unescaping &amp; first would turn the encoded "&lt;" into a real "<".
  const file = docx('<w:p><w:r><w:t>a &amp;lt; b &amp; c &#65;</w:t></w:r></w:p>')
  assert.equal(extractDocument(file, 'a.docx').text, 'a &lt; b & c A')
})

test('xlsx: shared strings are resolved, and blanks keep their index', () => {
  // t="s" is an INDEX into sharedStrings. Dropping the empty <si/> would shift
  // every later index by one — a spreadsheet that reads plausibly and is wrong.
  const file = zip([
    { name: 'xl/workbook.xml', data: '<workbook><sheets><sheet name="报表" sheetId="1"/></sheets></workbook>' },
    { name: 'xl/sharedStrings.xml', data: '<sst><si><t>甲</t></si><si><t></t></si><si><t>乙</t></si></sst>' },
    {
      name: 'xl/worksheets/sheet1.xml',
      data: '<worksheet><sheetData>' +
        '<row><c t="s"><v>0</v></c><c t="s"><v>2</v></c></row>' +
        '<row><c><v>42</v></c><c/></row>' +
        '</sheetData></worksheet>'
    }
  ])
  const result = extractDocument(file, 'a.xlsx')
  assert.equal(result.format, 'xlsx')
  assert.equal(result.text, '## 报表\n甲\t乙\n42\t')
})

test('xlsx: sheets are ordered by number, not by string', () => {
  const parts = [
    { name: 'xl/workbook.xml', data: '<workbook><sheets/></workbook>' }
  ]
  for (const n of [1, 2, 10]) {
    parts.push({
      name: `xl/worksheets/sheet${n}.xml`,
      data: `<worksheet><sheetData><row><c t="inlineStr"><is><t>S${n}</t></is></c></row></sheetData></worksheet>`
    })
  }
  const text = extractDocument(zip(parts), 'a.xlsx').text
  assert.deepEqual(text.match(/S\d+/g), ['S1', 'S2', 'S10'])
})

test('pptx: one block per slide, in slide order', () => {
  const file = zip([
    { name: 'ppt/slides/slide2.xml', data: '<p:sld><a:t>第二页</a:t></p:sld>' },
    { name: 'ppt/slides/slide1.xml', data: '<p:sld><a:t>标题</a:t><a:t>副标题</a:t></p:sld>' }
  ])
  const result = extractDocument(file, 'a.pptx')
  assert.equal(result.format, 'pptx')
  assert.equal(result.text, '## Slide 1\n标题\n副标题\n\n## Slide 2\n第二页')
})

test('a PDF is refused by name, whatever the file is called', () => {
  // Detection is by magic bytes: a PDF saved as report.docx must not be fed to
  // the ZIP reader, and the model must be told what it actually got.
  const pdf = Buffer.concat([Buffer.from('%PDF-1.7\n', 'latin1'), Buffer.alloc(16)])
  assert.throws(
    () => extractDocument(pdf, 'report.docx'),
    (error) => error instanceof ExtractError && error.code === 'PDF_UNSUPPORTED' && /report\.docx/.test(error.message)
  )
})

test('a plain text file is refused toward the read tool, not parsed', () => {
  assert.throws(
    () => extractDocument(Buffer.from('# 标题\n正文', 'utf8'), 'a.md'),
    (error) => error instanceof ExtractError && error.code === 'NOT_A_DOCUMENT'
  )
})

test('a ZIP that is not an Office file says which parts were missing', () => {
  assert.throws(
    () => extractDocument(zip([{ name: 'readme.txt', data: 'hi' }]), 'a.zip'),
    (error) => error instanceof ExtractError && error.code === 'UNSUPPORTED_ZIP'
  )
})

test('xlsx: an empty cell in the MIDDLE does not swallow the next one', () => {
  // Regression. Excel writes an empty cell as `<c r="B1"/>`. A pattern that
  // only knows `<c …>…</c>` matches that as an open tag and runs on to the
  // next cell's `</c>` — two columns become one, every later column shifts,
  // and the row still looks like plausible data. Trailing blanks are the
  // benign version of the same bug; this is the one that corrupts.
  const file = zip([
    { name: 'xl/workbook.xml', data: '<workbook><sheets><sheet name="S" sheetId="1"/></sheets></workbook>' },
    {
      name: 'xl/worksheets/sheet1.xml',
      data: '<worksheet><sheetData><row>' +
        '<c t="inlineStr"><is><t>A</t></is></c>' +
        '<c r="B1"/>' +
        '<c t="inlineStr"><is><t>C</t></is></c>' +
        '</row></sheetData></worksheet>'
    }
  ])
  assert.equal(extractDocument(file, 'a.xlsx').text, '## S\nA\t\tC')
})

test('xlsx: a self-closing empty row is still a row', () => {
  const file = zip([
    { name: 'xl/workbook.xml', data: '<workbook><sheets><sheet name="S" sheetId="1"/></sheets></workbook>' },
    {
      name: 'xl/worksheets/sheet1.xml',
      data: '<worksheet><sheetData>' +
        '<row><c t="inlineStr"><is><t>头</t></is></c></row>' +
        '<row r="2"/>' +
        '<row><c t="inlineStr"><is><t>尾</t></is></c></row>' +
        '</sheetData></worksheet>'
    }
  ])
  assert.equal(extractDocument(file, 'a.xlsx').text, '## S\n头\n\n尾')
})
