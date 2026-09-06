package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase

const val DEFAULT_ADDRESS_TENANT = "TENANT"
const val DEFAULT_ADDRESS_OWNER = "OWNER"

fun InvestigationCase.normalizedDefaultAddressType(): String =
    if (defaultAddressType == DEFAULT_ADDRESS_OWNER) DEFAULT_ADDRESS_OWNER else DEFAULT_ADDRESS_TENANT

fun InvestigationCase.defaultAddress(): String = when (normalizedDefaultAddressType()) {
    DEFAULT_ADDRESS_OWNER -> ownerAddress.trim()
    else -> propertyAddress.trim()
}

fun InvestigationCase.defaultAddressLabel(): String = when (normalizedDefaultAddressType()) {
    DEFAULT_ADDRESS_OWNER -> "소유자 주소"
    else -> "임차인 주소(물건 소재지)"
}
