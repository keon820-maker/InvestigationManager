package kr.co.investigation.manager.location

import android.annotation.TargetApi
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object GeocoderService {
    private const val RESOLVE_TIMEOUT_MS = 8_000L

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
        // One budget covers every variant, rather than starting a new timeout per query.
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            for(query in variants.filter { it.isNotBlank() }) {
                currentCoroutineContext().ensureActive()
                val found = try {
                    val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        resolveAsync(geocoder, query)
                    } else {
                        // Pre-33 has no cancellable API: this IO call can outlive the budget.
                        // Cancellation is checked on return, so no later variants are started.
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(query, 1)
                    }
                    addresses?.firstOrNull()?.let { it.latitude to it.longitude }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if(found != null) return@withTimeoutOrNull found
            }
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun resolveAsync(geocoder: Geocoder, query: String): List<Address>? =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            fun complete(addresses: List<Address>?) {
                if (completed.compareAndSet(false, true)) continuation.resume(addresses)
            }

            // Geocoder exposes no request cancellation. Ignore callbacks after cancellation
            // and guard against a provider delivering more than one terminal callback.
            continuation.invokeOnCancellation { completed.set(true) }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        complete(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        complete(null)
                    }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                complete(null)
            }
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
