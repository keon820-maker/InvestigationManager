package kr.co.investigation.manager.ocr

import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Draw synthetic cells on device; assertions exercise the same ML Kit and OpenCV as the app. */
class GridFormOcrInstrumentedTest {
    @Test fun scanKeepsEmptyTenantCellsAndRepeatedContacts() = verify(populatedTenant = false, perspective = false)
    @Test fun photoKeepsWrappedTenantPhoneInItsOwnCell() = verify(populatedTenant = true, perspective = true)

    private fun verify(populatedTenant: Boolean, perspective: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = drawForm(populatedTenant)
        val image = if (perspective) {
            val warped = Bitmap.createBitmap(2700, 3800, Bitmap.Config.ARGB_8888)
            val matrix = Matrix()
            matrix.setPolyToPoly(floatArrayOf(0f,0f,2480f,0f,2480f,3508f,0f,3508f),0,
                floatArrayOf(140f,100f,2600f,210f,2430f,3690f,70f,3540f),0,4)
            Canvas(warped).apply { drawColor(Color.DKGRAY); drawBitmap(source,matrix,Paint(Paint.FILTER_BITMAP_FLAG)) }
            warped
        } else source
        val file = File(context.cacheDir, "synthetic-grid.png")
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            val result = OcrService.recognizeCase(context, Uri.fromFile(file))
            assertTrue("Verified grid route was not used",result.preprocessMessage.contains("실제 칸 경계 확인"))
            val c=result.parsed
            assertEquals("가나다(900101-*)",c.debtorName)
            assertEquals("부동산 담보대출",c.loanType)
            assertEquals("2026-03-09",c.dueDate)
            assertEquals("010-0000-0000",c.phone)
            assertEquals("010-0000-0000",c.mobile)
            assertEquals("010-0000-0000",c.ownerPhone)
            assertEquals("",c.investigatorPhone)
            assertFalse(result.rawText.contains("제외대상"))
            val tenants=JSONArray(c.tenantsJson)
            assertEquals(if(populatedTenant) 1 else 0,tenants.length())
            if(populatedTenant) {
                assertEquals("라마바",tenants.getJSONObject(0).getString("name"))
                assertEquals("010-0000-0001",tenants.getJSONObject(0).getString("phone"))
            }
        } finally { file.delete(); if(image!==source) image.recycle();source.recycle() }
    }

    private fun drawForm(tenant:Boolean):Bitmap {
        val bitmap=Bitmap.createBitmap(2480,3508,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=3f}
        val font=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=36f;typeface=Typeface.DEFAULT}
        fun row(y:Int,height:Int,edges:List<Float>,values:List<String>) {
            for(i in 0 until edges.size-1) {
                val left=160+2160*edges[i]; val right=160+2160*edges[i+1]
                canvas.drawRect(left,y.toFloat(),right,(y+height).toFloat(),pen)
                values.getOrElse(i){""}.lines().forEachIndexed { line,text -> canvas.drawText(text,left+12,y+45f+line*38,font) }
            }
        }
        canvas.drawText("조 사 의 뢰 서",850f,230f,Paint(font).apply{textSize=80f})
        canvas.drawText("의뢰일 : 2026년 03월 04일",850f,350f,font)
        canvas.drawText("관리번호 : 테스트202603-00001",180f,430f,font)
        canvas.drawText("조사담당자 : 제외대상 Tel 010-9999-9999",180f,530f,font)
        row(800,100,listOf(0f,.14f,.30f,.44f,.60f,.74f,1f),listOf("채무자 명","가나다(900101-*)","전화번호","010-0000-0000","핸드폰번호","010-0000-0000"))
        row(900,100,listOf(0f,.14f,.30f,.44f,1f),listOf("완료요청일","2026-03-09","비고",""))
        row(1150,100,listOf(0f,.14f,.58f,.72f,1f),listOf("조사구분","임대차조사(현장조사)","대출종류","부동산담보대출"))
        row(1250,100,listOf(0f,.14f,.58f,.72f,1f),listOf("물건종류","아파트","",""))
        row(1350,100,listOf(0f,.14f,1f),listOf("물건소재지","12345 테스트시 가상로 1 101동 101호"))
        row(1450,100,listOf(0f,.14f,.28f,.58f,.72f,1f),listOf("물건소유자","성명","가나다(900101-*)","연락처","010-0000-0000"))
        row(1550,100,listOf(0f,.14f,1f),listOf("소유자주소","12345 테스트시 가상로 2 102동 102호"))
        for(i in 0..4) row(1660+i*100,100,listOf(0f,.14f,.25f,.34f,.5f,.64f,.75f,.84f,1f),
            listOf("임차인${i*2+1}(성명)",if(tenant&&i==0)"라마바" else "","전화번호",if(tenant&&i==0)"010-0000-\n0001" else "","임차인${i*2+2}(성명)","","전화번호",""))
        row(2370,300,listOf(0f,1f),listOf("합성 문서입니다. 방문 전 연락 요청"))
        canvas.drawText("농협영업점 : 테스트지점",1100f,2840f,font)
        canvas.drawText("조사의뢰자 : 사아자",1100f,2940f,font)
        canvas.drawText("전화번호 : 031-000-0000   팩스 : 031-000-0001",1100f,3040f,font)
        return bitmap
    }
}
