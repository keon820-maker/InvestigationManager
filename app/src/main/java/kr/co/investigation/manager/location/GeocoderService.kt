package kr.co.investigation.manager.location

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object GeocoderService {
    suspend fun resolve(context:Context,address:String):Pair<Double,Double>? = withContext(Dispatchers.IO) {
        if(address.isBlank()) return@withContext null

        val base = address
            .replace(Regex("^\\s*\\d{5,6}\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if(base.isBlank()) return@withContext null

        // Android Geocoder는 아파트 동/호수까지 붙으면 실패하는 기기가 있어
        // 상세주소를 단계적으로 줄여가며 재시도한다.
        val variants = linkedSetOf<String>()
        variants += base
        variants += base
            .replace(Regex("\\s+\\d{1,4}동\\s*\\d{1,4}호.*$"), "")
            .trim()
        variants += base
            .replace(Regex("\\s+\\d{1,4}동.*$"), "")
            .trim()

        // '경기'처럼 축약된 광역명을 정식 명칭으로 바꾼 후보도 시도한다.
        val expanded = variants.toList().map(::expandProvince)
        variants += expanded

        val geocoder = Geocoder(context, Locale.KOREA)
        for(query in variants.filter { it.isNotBlank() }) {
            val found = runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)
                    ?.firstOrNull()
                    ?.let { it.latitude to it.longitude }
            }.getOrNull()
            if(found != null) return@withContext found
        }
        null
    }

    private fun expandProvince(value: String): String {
        val pairs = listOf(
            "경기 " to "경기도 ",
            "강원 " to "강원특별자치도 ",
            "충북 " to "충청북도 ",
            "충남 " to "충청남도 ",
            "전북 " to "전북특별자치도 ",
            "전남 " to "전라남도 ",
            "경북 " to "경상북도 ",
            "경남 " to "경상남도 ",
            "제주 " to "제주특별자치도 "
        )
        return pairs.firstOrNull { value.startsWith(it.first) }
            ?.let { it.second + value.removePrefix(it.first) }
            ?: value
    }
}
