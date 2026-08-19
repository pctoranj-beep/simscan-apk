package ir.simscan.fast

import android.content.Context
import android.net.Uri
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {
    fun export(context: Context, uri: Uri, records: List<SimRecord>) {
        context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
            writeWorkbook(out, records.reversed())
        }
    }

    private fun writeWorkbook(output: OutputStream, records: List<SimRecord>) {
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""")

            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")

            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="SIMSCAN" sheetId="1" r:id="rId1"/></sheets>
</workbook>""")

            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")

            entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>""")

            val sheet = StringBuilder()
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>")
            sheet.append("<cols><col min=\"1\" max=\"1\" width=\"9\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"18\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"28\" customWidth=\"1\"/></cols><sheetData>")
            sheet.append(rowXml(1, listOf("ردیف", "شماره موبایل", "بارکد خطی"), true))
            records.forEachIndexed { index, r ->
                sheet.append(rowXml(index + 2, listOf((index + 1).toString(), r.phone, r.barcode), false))
            }
            sheet.append("</sheetData></worksheet>")
            entry("xl/worksheets/sheet1.xml", sheet.toString())
        }
    }

    private fun rowXml(row: Int, values: List<String>, header: Boolean): String {
        val cols = listOf("A", "B", "C")
        val sb = StringBuilder("<row r=\"$row\">")
        values.forEachIndexed { i, value ->
            val style = if (header) " s=\"1\"" else ""
            sb.append("<c r=\"${cols[i]}$row\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">")
            sb.append(xml(value))
            sb.append("</t></is></c>")
        }
        sb.append("</row>")
        return sb.toString()
    }

    private fun xml(s: String): String = buildString(s.length + 16) {
        s.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
