package com.bluewave.remote

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

enum class LaunchType {
    DEEP_LINK,
    NATIVE_LAUNCH,
}

data class LaunchResolution(val app: TvApp?, val detail: String)

object AppLauncher {
    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{M}ـ]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val aliases =
        mapOf(
            "YouTube" to listOf("youtube", "youtubeontv", "يوتيوب"),
            "Shahid" to listOf("shahid", "shahidvip", "شاهد"),
            "Yango Play" to listOf("yangoplay", "yangoplaytv", "يانغوبلاي", "يانجوبلاي"),
            "Netflix" to listOf("netflix", "نتفليكس"),
            "WATCH IT" to listOf("watchit", "واتشات"),
            "Prime Video" to listOf("primevideo", "amazonprimevideo"),
            "MBC" to listOf("mbc", "امبيسي"),
        )

    fun canonical(name: String) =
        aliases.entries.find { (_, names) -> normalize(name) in names.map(::normalize) }?.key
            ?: normalize(name)

    fun validId(id: String) = id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)

    fun parseApp(a: JSONObject): TvApp? {
        val id = a.optString("appId")
        if (!validId(id)) return null
        val raw = a.opt("app_type") ?: a.opt("appType")
        val code = raw?.toString()?.toIntOrNull()
        val explicit = a.optString("action_type")
        val type =
            when {
                explicit == "NATIVE_LAUNCH" -> LaunchType.NATIVE_LAUNCH
                explicit == "DEEP_LINK" -> LaunchType.DEEP_LINK
                code == 0 || code == 1 -> LaunchType.NATIVE_LAUNCH
                else -> LaunchType.DEEP_LINK
            }
        return TvApp(
            id,
            a.optString("name", id).take(256),
            type,
            code in listOf(0, 1, 2) || explicit in LaunchType.entries.map { it.name },
        )
    }

    fun resolve(request: TvApp, installed: List<TvApp>, shortcuts: List<TvApp>): LaunchResolution {
        val configured =
            if (request.id.isNotBlank()) shortcuts.filter { it.id == request.id }
            else shortcuts.filter { canonical(it.name) == canonical(request.name) }
        if (configured.map { it.id }.distinct().size > 1)
            return LaunchResolution(
                null,
                "Multiple configured IDs match. Select a specific shortcut in Apps.",
            )
        val chosen = configured.firstOrNull()
        val id = chosen?.id ?: request.id
        if (id.isNotBlank()) {
            if (!validId(id)) return LaunchResolution(null, "Invalid TV application ID")
            val observed = installed.find { it.id == id }
            if (observed != null)
                return LaunchResolution(
                    if (chosen?.overrideType == true)
                        observed.copy(
                            launchType = chosen.launchType,
                            typeKnown = true,
                            overrideType = true,
                        )
                    else observed,
                    "TV-reported ID and launch type, when available",
                )
            if (chosen != null)
                return LaunchResolution(
                    chosen,
                    "User-configured ID/type; current TV installation is unverified",
                )
            return LaunchResolution(
                null,
                "This ID is not in the current TV app list. Configure it explicitly or refresh apps.",
            )
        }
        val matches =
            installed.filter { canonical(it.name) == canonical(request.name) }.distinctBy { it.id }
        return when (matches.size) {
            1 ->
                LaunchResolution(
                    matches.single(),
                    "Matched TV-reported app using explicit name aliases; " +
                        if (matches.single().typeKnown) "TV launch type available"
                        else "type unavailable, trying DEEP_LINK; configure NATIVE_LAUNCH if needed",
                )
            0 ->
                LaunchResolution(
                    null,
                    "No matching TV app ID. Refresh installed apps, configure an ID/type, or use the explicit Home fallback.",
                )
            else ->
                LaunchResolution(
                    null,
                    "Ambiguous TV app names. Select the installed ID or configure a specific shortcut.",
                )
        }
    }
}
