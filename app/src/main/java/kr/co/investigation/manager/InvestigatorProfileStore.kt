package kr.co.investigation.manager

import android.content.Context
import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray
import org.json.JSONObject

data class InvestigatorProfile(
    val name: String = "",
    val phone: String = ""
) {
    val isConfigured: Boolean
        get() = name.trim().isNotBlank() && phone.filter(Char::isDigit).length in 9..11

    fun applyTo(value: InvestigationCase): InvestigationCase {
        if (!isConfigured) return value
        val investigatorDigits = phone.filter(Char::isDigit)
        fun clearIfInvestigator(candidate: String): String =
            if (candidate.filter(Char::isDigit) == investigatorDigits) "" else candidate

        return value.copy(
            investigator = name.trim(),
            investigatorPhone = formatPhone(phone),
            investigatorFax = "",
            phone = clearIfInvestigator(value.phone),
            mobile = clearIfInvestigator(value.mobile),
            ownerPhone = clearIfInvestigator(value.ownerPhone),
            tenantsJson = sanitizeTenantPhones(value.tenantsJson, investigatorDigits)
        )
    }

    private fun sanitizeTenantPhones(json: String, investigatorDigits: String): String {
        if (investigatorDigits.isBlank()) return json
        return runCatching {
            val source = JSONArray(json.ifBlank { "[]" })
            val out = JSONArray()
            for (i in 0 until source.length()) {
                val original = source.optJSONObject(i)
                if (original == null) {
                    out.put(source.opt(i))
                    continue
                }
                val copy = JSONObject(original.toString())
                val phoneKeys = listOf("phone", "mobile")
                phoneKeys.forEach { key ->
                    val candidate = copy.optString(key)
                    if (candidate.filter(Char::isDigit) == investigatorDigits) {
                        copy.put(key, "")
                    }
                }
                out.put(copy)
            }
            out.toString()
        }.getOrDefault(json)
    }

    private fun formatPhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            digits.length == 11 && digits.startsWith("01") ->
                "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
            digits.length == 10 && digits.startsWith("02") ->
                "02-${digits.substring(2, 6)}-${digits.substring(6)}"
            digits.length == 10 ->
                "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
            digits.length == 9 && digits.startsWith("02") ->
                "02-${digits.substring(2, 5)}-${digits.substring(5)}"
            else -> value.trim()
        }
    }
}

object InvestigatorProfileStore {
    private const val PREFS = "private_investigator_profile"
    private const val KEY_NAME = "name"
    private const val KEY_PHONE = "phone"

    fun load(context: Context): InvestigatorProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return InvestigatorProfile(
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            phone = prefs.getString(KEY_PHONE, "").orEmpty()
        )
    }

    fun save(context: Context, profile: InvestigatorProfile) {
        require(profile.isConfigured) { "담당자 이름과 전화번호를 확인하세요." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_PHONE, profile.phone.filter(Char::isDigit))
            .apply()
    }
}
