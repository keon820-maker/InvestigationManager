package kr.co.investigation.manager

import androidx.compose.runtime.Composable

/** MainFlowV26에서 saveable 상태를 간단히 사용할 수 있게 하는 패키지 내부 호환 래퍼. */
@Composable
fun <T : Any> rememberSaveable(init: () -> T): T =
    androidx.compose.runtime.saveable.rememberSaveable(init = init)
