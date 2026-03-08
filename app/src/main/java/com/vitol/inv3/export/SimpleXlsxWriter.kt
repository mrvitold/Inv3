package com.vitol.inv3.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lightweight XLSX writer using pure Java (no Apache POI).
 * Generates valid .xlsx files compatible with Excel and Google Sheets.
 * XLSX is a ZIP archive of XML files per OOXML spec.
 */
class SimpleXlsxWriter(private val sheetName: String = "Sheet1") {

    private val sharedStrings = mutableListOf<String>()
    private val rows = mutableListOf<List<CellValue>>()

    sealed class CellValue {
        data class Text(val value: String) : CellValue()
        data class Number(val value: Double) : CellValue()
    }

    fun addRow(vararg cells: Any) {
        rows.add(cells.map { cell ->
            when (cell) {
                is String -> CellValue.Text(cell)
                is Double -> CellValue.Number(cell)
                is Float -> CellValue.Number(cell.toDouble())
                is Int -> CellValue.Number(cell.toDouble())
                is Long -> CellValue.Number(cell.toDouble())
                is Number -> CellValue.Number(cell.toDouble())
                else -> CellValue.Text(cell.toString())
            }
        })
    }

    fun writeTo(out: OutputStream) {
        // Build shared strings first (populated during sheet iteration)
        buildSharedStrings()
        ZipOutputStream(out).use { zos ->
            writeContentTypes(zos)
            writeRels(zos)
            writeWorkbookRels(zos)
            writeWorkbook(zos)
            writeSharedStrings(zos)
            writeStyles(zos)
            writeSheet(zos)
            writeDocPropsApp(zos)
            writeDocPropsCore(zos)
        }
    }

    private fun buildSharedStrings() {
        sharedStrings.clear()
        rows.forEach { cells ->
            cells.forEach { cell ->
                if (cell is CellValue.Text) {
                    if (cell.value !in sharedStrings) {
                        sharedStrings.add(cell.value)
                    }
                }
            }
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun writeEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeContentTypes(zos: ZipOutputStream) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""
        writeEntry(zos, "[Content_Types].xml", xml)
    }

    private fun writeRels(zos: ZipOutputStream) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""
        writeEntry(zos, "_rels/.rels", xml)
    }

    private fun writeWorkbookRels(zos: ZipOutputStream) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
        writeEntry(zos, "xl/_rels/workbook.xml.rels", xml)
    }

    private fun writeWorkbook(zos: ZipOutputStream) {
        val escapedName = escapeXml(sheetName)
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$escapedName" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
        writeEntry(zos, "xl/workbook.xml", xml)
    }

    private fun writeSharedStrings(zos: ZipOutputStream) {
        val items = sharedStrings.joinToString("") { s ->
            "    <si><t>${escapeXml(s)}</t></si>\n"
        }
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">
$items</sst>"""
        writeEntry(zos, "xl/sharedStrings.xml", xml)
    }

    private fun writeStyles(zos: ZipOutputStream) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>"""
        writeEntry(zos, "xl/styles.xml", xml)
    }

    private fun indexOfSharedString(s: String): Int =
        sharedStrings.indexOf(s).takeIf { it >= 0 } ?: sharedStrings.apply { add(s) }.lastIndex

    private fun writeSheet(zos: ZipOutputStream) {
        val sb = StringBuilder()
        rows.forEachIndexed { rowIdx, cells ->
            sb.append("    <row r=\"${rowIdx + 1}\">\n")
            cells.forEachIndexed { colIdx, cell ->
                val cellRef = colToRef(colIdx) + (rowIdx + 1)
                when (cell) {
                    is CellValue.Text -> {
                        val idx = indexOfSharedString(cell.value)
                        sb.append("      <c r=\"$cellRef\" t=\"s\"><v>$idx</v></c>\n")
                    }
                    is CellValue.Number -> {
                        sb.append("      <c r=\"$cellRef\"><v>${cell.value}</v></c>\n")
                    }
                }
            }
            sb.append("    </row>\n")
        }
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
$sb  </sheetData>
  <pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
</worksheet>"""
        writeEntry(zos, "xl/worksheets/sheet1.xml", xml)
    }

    private fun colToRef(col: Int): String {
        var c = col
        var result = ""
        do {
            result = ('A' + (c % 26)) + result
            c = c / 26 - 1
        } while (c >= 0)
        return result
    }

    private fun writeDocPropsApp(zos: ZipOutputStream) {
        val escapedName = escapeXml(sheetName)
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>Inv3</Application>
  <DocSecurity>0</DocSecurity>
  <ScaleCrop>false</ScaleCrop>
  <HeadingPairs><vt:vector xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes" size="2"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>1</vt:i4></vt:variant></vt:vector></HeadingPairs>
  <TitlesOfParts><vt:vector xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes" size="1"><vt:lpstr>$escapedName</vt:lpstr></vt:vector></TitlesOfParts>
  <Company></Company>
  <LinksUpToDate>false</LinksUpToDate>
  <SharedDoc>false</SharedDoc>
  <HyperlinksChanged>false</HyperlinksChanged>
  <AppVersion>1.0</AppVersion>
</Properties>"""
        writeEntry(zos, "docProps/app.xml", xml)
    }

    private fun writeDocPropsCore(zos: ZipOutputStream) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:creator>Inv3</dc:creator>
  <cp:lastModifiedBy>Inv3</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
</cp:coreProperties>"""
        writeEntry(zos, "docProps/core.xml", xml)
    }
}
