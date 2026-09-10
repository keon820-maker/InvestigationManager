package kr.co.investigation.manager

data class DetailSaveStatus(
    val caseId: Long = 0,
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false
)
