package kr.co.investigation.manager.archive

import android.content.Context
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.investigation.manager.data.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

object ArchiveService {
    data class ExportResult(val file:File,val cases:Int,val attachments:Int,val verified:Boolean)

    suspend fun exportYear(context:Context,db:AppDb,year:Int):ExportResult = withContext(Dispatchers.IO) {
        val cases=db.cases().getYear(year)
        val atts=if(cases.isEmpty()) emptyList() else db.attachments().getForCases(cases.map{it.id})
        val outDir=File(context.getExternalFilesDir(null),"exports").apply{mkdirs()}
        val zip=File(outDir,"조사관리_${year}_${System.currentTimeMillis()}.zip")
        val gson=GsonBuilder().setPrettyPrinting().create()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { z ->
            z.setLevel(Deflater.BEST_SPEED)
            fun bytes(name:String,data:ByteArray){ z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            bytes("data/cases.json",gson.toJson(cases).toByteArray())
            bytes("data/attachments.json",gson.toJson(atts).toByteArray())
            val csv=buildString {
                appendLine("id,관리번호,의뢰일,조사담당자,담당자전화,담당자Fax,채무자,완료요청일,조사예정일,방문순서,물건소재지,영업점,영업점전화,영업점Fax,조사의뢰자,상태,조사시작시간,조사완료시간,비고")
                cases.forEach{c->
                    appendLine(
                        listOf(
                            c.id,c.managementNo,c.requestDate,c.investigator,c.investigatorPhone,c.investigatorFax,
                            c.debtorName,c.dueDate,c.plannedDate,c.routeOrder,c.propertyAddress,c.branch,c.branchPhone,c.branchFax,c.requester,
                            c.status,c.startedAt ?: "",c.completedAt ?: "",c.investigationMemo
                        ).joinToString(","){v->"\"${v.toString().replace("\"","\"\"")}\""}
                    )
                }
            }
            bytes("data/조사목록.csv",csv.toByteArray())
            val manifest=StringBuilder("path,sha256,size\n")
            atts.forEach { a ->
                val f=File(a.localPath); if(f.exists()){
                    val entry="files/${a.caseId}/${f.name}"
                    z.putNextEntry(ZipEntry(entry))
                    val digest = MessageDigest.getInstance("SHA-256")
                    f.inputStream().buffered().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            z.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    z.closeEntry()
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    manifest.append("$entry,$sha256,${f.length()}\n")
                }
            }
            bytes("data/SHA256_MANIFEST.csv",manifest.toString().toByteArray())
        }
        val verified=verifyZip(zip)
        ExportResult(zip,cases.size,atts.size,verified)
    }

    private fun verifyZip(file:File):Boolean=runCatching {
        ZipFile(file).use{z-> val e=z.entries(); while(e.hasMoreElements()){ val x=e.nextElement(); if(!x.isDirectory) z.getInputStream(x).use{it.copyTo(OutputStream.nullOutputStream(), COPY_BUFFER_SIZE)} }}; true
    }.getOrDefault(false)

    private const val COPY_BUFFER_SIZE = 256 * 1024
}
