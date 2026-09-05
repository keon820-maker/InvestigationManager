package kr.co.investigation.manager

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kr.co.investigation.manager.data.InvestigationCase

/** 이전 화면 소스가 컴파일될 수 있도록 현재 카카오맵 구현으로 연결한다. */
@Composable
fun NativeMapPane(
    items: List<InvestigationCase>,
    selected: InvestigationCase?,
    modifier: Modifier = Modifier
) {
    NativeMapPaneV29(
        items = items,
        selected = selected,
        onNavigate = {},
        modifier = modifier
    )
}
