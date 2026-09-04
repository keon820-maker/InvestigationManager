package kr.co.investigation.manager.archive

import android.content.Context
import com.google.gson.GsonBuilder
import kr.co.investigation.manager.data.*
import kr.co.investigation.manager.storage.OriginalFileStore
import java.io.*
import java.util.zip.*

object ArchiveService {
    data class ExportResult(val file:File,val cases:Int,val attachments:Int,val verified:Boolean)

    suspend fun exportYear(context:Context,db:AppDb,year:Int):ExportResult {
        val cases=db.cases().getYear(year)
        val atts=if(cases.isEmpty()) emptyList() else db.attachments().getForCases(cases.map{it.id})
        val outDir=File(context.getExternalFilesDir(null),"exports").apply{mkdirs()}
        val zip=File(outDir,"조사관리_${year}_${System.currentTimeMillis()}.zip")
        val gson=GsonBuilder().setPrettyPrinting().create()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { z ->
            fun bytes(name:String,data:ByteArray){ z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            bytes("data/cases.json",gson.toJson(cases).toByteArray())
            bytes("data/attachments.json",gson.toJson(atts).toByteArray())
            val csv=buildString {
                appendLine("id,관리번호,의뢰일,조사담당자,담당자전화,담당자Fax,채무자,완료요청일,조사예정일,물건소재지,영업점,영업점전화,영업점Fax,조사의뢰자,상태,비고")
                cases.forEach{c->
                    appendLine(
                        listOf(
                            c.id,c.managementNo,c.requestDate,c.investigator,c.investigatorPhone,c.investigatorFax,
                            c.debtorName,c.dueDate,c.plannedDate,c.propertyAddress,c.branch,c.branchPhone,c.branchFax,c.requester,
                            c.status,c.investigationMemo
                        ).joinToString(","){v->"\"${v.toString().replace("\"","\"\"")}\""}
                    )
                }
            }
            bytes("data/조사목록.csv",csv.toByteArray())
            val manifest=StringBuilder("path,sha256,size\n")
            atts.forEach { a ->
                val f=File(a.localPath); if(f.exists()){
                    val entry="files/${a.caseId}/${f.name}"; z.putNextEntry(ZipEntry(entry)); f.inputStream().use{it.copyTo(z)}; z.closeEntry()
                    manifest.append("$entry,${OriginalFileStore.sha256(f)},${f.length()}\n")
                }
            }
            bytes("data/SHA256_MANIFEST.csv",manifest.toString().toByteArray())
        }
        val verified=verifyZip(zip)
        return ExportResult(zip,cases.size,atts.size,verified)
    }

    private fun verifyZip(file:File):Boolean=runCatching {
        ZipFile(file).use{z-> val e=z.entries(); while(e.hasMoreElements()){ val x=e.nextElement(); if(!x.isDirectory) z.getInputStream(x).use{it.copyTo(OutputStream.nullOutputStream())} }}; true
    }.getOrDefault(false)
}
