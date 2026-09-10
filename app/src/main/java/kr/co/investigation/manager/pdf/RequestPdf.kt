package kr.co.investigation.manager.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.documentMapAddress
import java.io.File
import java.io.FileOutputStream

object RequestPdf {
    // A4 595x842pt 기준 고정 레이아웃. 화면 크기와 무관하게 동일 출력.
    fun create(context:Context,c:InvestigationCase):File {
        val pdf=PdfDocument(); val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create()); val x=page.canvas
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;typeface=Typeface.create("sans",Typeface.NORMAL)}
        fun text(s:String,xx:Float,yy:Float,size:Float=9f,bold:Boolean=false){p.textSize=size;p.typeface=Typeface.create("sans",if(bold)Typeface.BOLD else Typeface.NORMAL);x.drawText(s,xx,yy,p)}
        fun line(x1:Float,y1:Float,x2:Float,y2:Float){p.strokeWidth=.8f;x.drawLine(x1,y1,x2,y2,p)}
        fun box(l:Float,t:Float,r:Float,b:Float){p.style=Paint.Style.STROKE;x.drawRect(l,t,r,b,p);p.style=Paint.Style.FILL}
        fun dash(v:String)=v.ifBlank{"-"}
        text("조 사 의 뢰 서",245f,55f,18f,true); text("[ 의뢰일 : ${c.requestDate} ]",238f,75f,9f)
        text("○ 관리번호 : ${c.managementNo}",55f,105f)
        text("○ 조사담당자 : ${c.investigator}",55f,125f)
        text("Tel) ${dash(c.investigatorPhone)}",265f,125f)
        text("Fax) ${dash(c.investigatorFax)}",425f,125f)
        text("1. 대 상 자",55f,160f,11f,true)
        val l=55f; val r=540f; var y=175f; box(l,y,r,y+58)
        line(140f,y,140f,y+58); line(310f,y,310f,y+58); line(390f,y,390f,y+58); line(l,y+29,r,y+29)
        text("채무자 명",70f,y+19);text(c.debtorName,150f,y+19);text("전화번호",320f,y+19);text(c.phone,400f,y+19)
        text("완료요청일",65f,y+48);text(c.dueDate,150f,y+48);text("핸드폰번호",315f,y+48);text(c.mobile,400f,y+48)
        text("2. 의뢰 내용",55f,260f,11f,true); y=275f; box(l,y,r,y+205)
        val rows=listOf("조사구분" to c.investigationType,"대출종류" to c.loanType,"물건종류" to c.propertyType,"물건소재지" to c.propertyAddress,"물건소유자" to c.ownerName,"소유자 주소" to c.ownerAddress)
        var yy=y; rows.forEachIndexed{idx,(k,v)-> val h=if(idx==3)40f else 28f; line(l,yy+h,r,yy+h); line(140f,yy,140f,yy+h); text(k,70f,yy+18); text(v.take(58),150f,yy+18); yy+=h }
        text("3. 기타요청사항",55f,515f,11f,true); box(l,530f,r,625f); text(c.requestNotes.take(80),70f,558f,9f)
        text("▷ 농협영업점 : ${c.branch}",330f,655f,9f,true)
        text("▷ 조사의뢰자 : ${c.requester}",330f,680f,9f,true)
        text("▷ 전화번호 : ${dash(c.branchPhone)}",330f,705f,9f,true)
        text("   팩스 : ${dash(c.branchFax)}",430f,705f,9f,true)
        val mapAddress = c.documentMapAddress()
        val addressLines = mutableListOf<String>()
        p.textSize = 9f
        for (paragraph in mapAddress.lines()) {
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val count = p.breakText(remaining, true, 465f, null).coerceAtLeast(1)
                addressLines += remaining.take(count)
                remaining = remaining.drop(count)
            }
        }
        if (addressLines.isNotEmpty()) {
            text("지도 표시 주소(직접입력)",55f,735f,10f,true)
            if(addressLines.size <= 3) addressLines.forEachIndexed { index, value -> text(value,55f,754f+13f*index) }
            else text("직접 입력한 주소는 다음 페이지에 표시됩니다.",55f,754f)
        }
        text("※ 앱에서 원본 양식에 맞춰 재생성된 문서",55f,800f,7f)
        pdf.finishPage(page)
        if(addressLines.size > 3) addressLines.chunked(52).forEachIndexed { index, lines ->
            val extra = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,index+2).create())
            p.textSize=12f
            extra.canvas.drawText("지도 표시 주소(직접입력)",55f,55f,p)
            p.textSize=9f
            lines.forEachIndexed { lineIndex, value -> extra.canvas.drawText(value,55f,85f+13f*lineIndex,p) }
            pdf.finishPage(extra)
        }
        val dir=File(context.getExternalFilesDir(null),"pdf/${c.year}").apply{mkdirs()}; val f=File(dir,"${c.managementNo.ifBlank{c.id.toString()}}_조사의뢰서.pdf")
        FileOutputStream(f).use{pdf.writeTo(it)};pdf.close();return f
    }
}
