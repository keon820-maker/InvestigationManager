package kr.co.investigation.manager.location

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object GeocoderService {
    suspend fun resolve(context:Context,address:String):Pair<Double,Double>? = withContext(Dispatchers.IO) {
        if(address.isBlank()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.KOREA).getFromLocationName(address,1)?.firstOrNull()?.let{it.latitude to it.longitude}
        }.getOrNull()
    }
}
