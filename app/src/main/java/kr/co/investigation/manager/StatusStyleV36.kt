package kr.co.investigation.manager

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal const val CASE_STATUS_NEW_V36 = "신규"
internal const val CASE_STATUS_PROGRESS_V36 = "진행중"
internal const val CASE_STATUS_CANCELLED_V36 = "의뢰취소"
internal const val CASE_STATUS_DONE_V36 = "완료"

internal val CASE_STATUS_VALUES_V36 = listOf(
    CASE_STATUS_NEW_V36,
    CASE_STATUS_PROGRESS_V36,
    CASE_STATUS_CANCELLED_V36,
    CASE_STATUS_DONE_V36
)

internal fun normalizeCaseStatusV36(value: String): String = when (value.trim()) {
    CASE_STATUS_PROGRESS_V36 -> CASE_STATUS_PROGRESS_V36
    CASE_STATUS_CANCELLED_V36 -> CASE_STATUS_CANCELLED_V36
    CASE_STATUS_DONE_V36 -> CASE_STATUS_DONE_V36
    else -> CASE_STATUS_NEW_V36
}

@Composable
internal fun statusContainerColorV36(value: String): Color = when (normalizeCaseStatusV36(value)) {
    CASE_STATUS_PROGRESS_V36 -> MaterialTheme.colorScheme.primaryContainer
    CASE_STATUS_CANCELLED_V36 -> MaterialTheme.colorScheme.errorContainer
    CASE_STATUS_DONE_V36 -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
internal fun statusContentColorV36(value: String): Color = when (normalizeCaseStatusV36(value)) {
    CASE_STATUS_PROGRESS_V36 -> MaterialTheme.colorScheme.onPrimaryContainer
    CASE_STATUS_CANCELLED_V36 -> MaterialTheme.colorScheme.onErrorContainer
    CASE_STATUS_DONE_V36 -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSecondaryContainer
}

internal fun changeDraftStatusV36(
    value: kr.co.investigation.manager.data.InvestigationCase,
    status: String,
    now: Long = System.currentTimeMillis()
): kr.co.investigation.manager.data.InvestigationCase = when (normalizeCaseStatusV36(status)) {
    CASE_STATUS_PROGRESS_V36 -> value.copy(
        status = CASE_STATUS_PROGRESS_V36,
        startedAt = value.startedAt ?: now,
        completedAt = null
    )
    CASE_STATUS_DONE_V36 -> value.copy(
        status = CASE_STATUS_DONE_V36,
        startedAt = value.startedAt ?: now,
        completedAt = if (normalizeCaseStatusV36(value.status) == CASE_STATUS_DONE_V36) {
            value.completedAt ?: now
        } else {
            now
        }
    )
    CASE_STATUS_CANCELLED_V36 -> value.copy(
        status = CASE_STATUS_CANCELLED_V36,
        startedAt = null,
        completedAt = null
    )
    else -> value.copy(
        status = CASE_STATUS_NEW_V36,
        startedAt = null,
        completedAt = null
    )
}
