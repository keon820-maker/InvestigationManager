package kr.co.investigation.manager.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {
    suspend fun recognize(context:Context, uri:Uri):String = suspendCancellableCoroutine { c ->
        val image=InputImage.fromFilePath(context,uri)
        val client=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        client.process(image).addOnSuccessListener{ c.resume(it.text) }.addOnFailureListener{c.resumeWithException(it)}
    }

    // 고정 양식용 1차 파서. 저장 전 검수 화면에서 반드시 사람이 확인/수정하도록 설계.
    fun parse(text:String):InvestigationCase {
        fun one(vararg labels:String):String {
            val lines=text.lines().map{it.trim()}.filter{it.isNotBlank()}
            for(i in lines.indices) for(label in labels) if(lines[i].contains(label)) {
                val inline=lines[i].substringAfter(label).replace(Regex("^[ :：]+"),"").trim()
                if(inline.isNotBlank()) return inline
                if(i+1<lines.size) return lines[i+1]
            }
            return ""
        }
        fun dateAfter(label:String):String {
            val r=Regex("${Regex.escape(label)}[^0-9]*(20\\d{2})[-년 .]*(\\d{1,2})[-월 .]*(\\d{1,2})")
            val m=r.find(text)?:return ""
            return "%04d-%02d-%02d".format(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt())
        }
        val addressRegex=Regex("(?:물건\\s*소재지)[\\s:：]*([^\\n]+)")
        val address=addressRegex.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val reqDate=dateAfter("의뢰일")
        return InvestigationCase(
            year=reqDate.take(4).toIntOrNull()?:LocalDate.now().year,
            managementNo=one("관리번호"), requestDate=reqDate, investigator=one("조사담당자"),
            debtorName=one("채무자 명","채무자명"), phone=one("전화번호"), mobile=one("핸드폰번호"),
            dueDate=dateAfter("완료요청일"), investigationType=one("조사구분"), loanType=one("대출종류"),
            propertyType=one("물건종류"), propertyAddress=address, ownerName=one("물건 소유자","물건소유자"),
            ownerResidentNo=one("주민번호"), ownerPhone=one("연락처"), ownerAddress=one("소유자 주소"),
            requestNotes=one("기타요청사항"), branch=one("농협영업점","영업점"), requester=one("조사의뢰자")
        )
    }
}
