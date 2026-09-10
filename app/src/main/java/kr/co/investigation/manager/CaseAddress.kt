package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase

const val DEFAULT_ADDRESS_TENANT = "TENANT"
const val DEFAULT_ADDRESS_OWNER = "OWNER"
const val DEFAULT_ADDRESS_CUSTOM = "CUSTOM"

fun InvestigationCase.normalizedDefaultAddressType(): String = when(defaultAddressType) {
    DEFAULT_ADDRESS_OWNER -> DEFAULT_ADDRESS_OWNER
    DEFAULT_ADDRESS_CUSTOM -> DEFAULT_ADDRESS_CUSTOM
    else -> DEFAULT_ADDRESS_TENANT
}

fun InvestigationCase.defaultAddress(): String = when (normalizedDefaultAddressType()) {
    DEFAULT_ADDRESS_OWNER -> ownerAddress.trim()
    DEFAULT_ADDRESS_CUSTOM -> customMapAddress.trim()
    else -> propertyAddress.trim()
}

fun InvestigationCase.defaultAddressLabel(): String = when (normalizedDefaultAddressType()) {
    DEFAULT_ADDRESS_OWNER -> "소유자 주소"
    DEFAULT_ADDRESS_CUSTOM -> "직접입력 주소"
    else -> "물건소재지"
}

fun InvestigationCase.documentMapAddress(): String =
    if (normalizedDefaultAddressType() == DEFAULT_ADDRESS_CUSTOM) customMapAddress.trim() else ""
