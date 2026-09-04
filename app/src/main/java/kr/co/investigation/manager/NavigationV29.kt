package kr.co.investigation.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.location.GeocoderService
import org.json.JSONArray

data class NavigationTargetV29(
    val label: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

data class PhoneTargetV29(
    val label: String,
    val number: String
)

suspend fun resolveNavigationTargetV29(
    context: Context,
    c: InvestigationCase,
    ownerAddress: Boolean
): NavigationTargetV29? {
    val address = if (ownerAddress) c.ownerAddress.trim() else c.propertyAddress.trim()
    if (address.isBlank()) return null

    val xy = if (!ownerAddress && c.propertyLatitude != null && c.propertyLongitude != null) {
        c.propertyLatitude to c.propertyLongitude
    } else {
        GeocoderService.resolve(context, address)
    } ?: return null

    return NavigationTargetV29(
        label = if (ownerAddress) "소유자 주소" else "물건 소재지",
        address = address,
        latitude = xy.first,
        longitude = xy.second
    )
}

fun openTmapTargetV29(context: Context, target: NavigationTargetV29) {
    val name = target.address.ifBlank { target.label }
    val uri = Uri.parse(
        "tmap://route?goalname=${Uri.encode(name)}&goalx=${target.longitude}&goaly=${target.latitude}"
    )
    val launched = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    if (!launched) {
        val market = Uri.parse("market://details?id=com.skt.tmap.ku")
        val web = Uri.parse("https://play.google.com/store/apps/details?id=com.skt.tmap.ku")
        if (runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isFailure
        ) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

fun openKakaoTargetV29(context: Context, target: NavigationTargetV29) {
    val name = target.address.ifBlank { target.label }
    val uri = Uri.parse(
        "https://map.kakao.com/link/to/${Uri.encode(name)},${target.latitude},${target.longitude}"
    )
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun phoneTargetsV29(c: InvestigationCase): List<PhoneTargetV29> {
    val result = mutableListOf<PhoneTargetV29>()

    runCatching { JSONArray(c.tenantsJson) }.getOrNull()?.let { rows ->
        for (i in 0 until minOf(rows.length(), 10)) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").ifBlank { row.optString("tenantName") }.trim()
            val phone = normalizePhoneTargetV29(
                row.optString("phone").ifBlank { row.optString("mobile") }
            )
            if (phone.isNotBlank()) {
                result += PhoneTargetV29(
                    label = if (name.isNotBlank()) "임차인 ${i + 1} · $name" else "임차인 ${i + 1}",
                    number = phone
                )
            }
        }
    }

    normalizePhoneTargetV29(c.ownerPhone).takeIf { it.isNotBlank() }?.let {
        result += PhoneTargetV29(
            label = if (c.ownerName.isNotBlank()) "물건 소유자 · ${c.ownerName}" else "물건 소유자",
            number = it
        )
    }

    val debtorMobile = normalizePhoneTargetV29(c.mobile)
    val debtorPhone = normalizePhoneTargetV29(c.phone)
    when {
        debtorMobile.isNotBlank() -> result += PhoneTargetV29("채무자 · 휴대폰", debtorMobile)
        debtorPhone.isNotBlank() -> result += PhoneTargetV29("채무자", debtorPhone)
    }
    if (debtorPhone.isNotBlank() && debtorPhone != debtorMobile) {
        result += PhoneTargetV29("채무자 · 전화", debtorPhone)
    }

    return result.distinctBy { it.label to it.number }
}

fun dialPhoneTargetV29(context: Context, target: PhoneTargetV29) {
    if (target.number.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target.number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun normalizePhoneTargetV29(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length == 11 && digits.startsWith("01") ->
            "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
        digits.length == 10 && digits.startsWith("02") ->
            "02-${digits.substring(2, 6)}-${digits.substring(6)}"
        digits.length == 9 && digits.startsWith("02") ->
            "02-${digits.substring(2, 5)}-${digits.substring(5)}"
        digits.length == 10 ->
            "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
        else -> value.trim()
    }
}
