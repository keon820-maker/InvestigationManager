package kr.co.investigation.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import kr.co.investigation.manager.data.InvestigationCase

fun navigationDestinationNameV28(c: InvestigationCase): String = c.propertyAddress.ifBlank {
    c.managementNo.ifBlank { c.debtorName.ifBlank { "조사 목적지" } }
}

fun openTmapV28(context: Context, c: InvestigationCase) {
    val lat = c.propertyLatitude ?: return
    val lon = c.propertyLongitude ?: return
    val uri = Uri.parse(
        "tmap://route?goalname=${Uri.encode(navigationDestinationNameV28(c))}&goalx=$lon&goaly=$lat"
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

fun openKakaoRouteV28(context: Context, c: InvestigationCase) {
    val lat = c.propertyLatitude ?: return
    val lon = c.propertyLongitude ?: return
    val uri = Uri.parse(
        "https://map.kakao.com/link/to/${Uri.encode(navigationDestinationNameV28(c))},$lat,$lon"
    )
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun dialCaseV28(context: Context, c: InvestigationCase) {
    val number = c.mobile.ifBlank { c.phone }.ifBlank { c.ownerPhone }
    if (number.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
    }
}
