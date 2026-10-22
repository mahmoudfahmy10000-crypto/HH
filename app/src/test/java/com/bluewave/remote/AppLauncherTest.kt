package com.bluewave.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppLauncherTest {
    @Test
    fun usesReturnedRegionalIdsAndExactAliasesForAllBrands() {
        listOf(
                "YouTube" to "YouTube on TV",
                "Shahid" to "شاهد",
                "Yango Play" to "يانغو بلاي",
                "Netflix" to "Netflix",
                "WATCH IT" to "WATCH IT",
                "Prime Video" to "Amazon Prime Video",
                "MBC" to "MBC",
            )
            .forEachIndexed { index, (brand, returned) ->
                val installed = TvApp("region-$index", returned, LaunchType.NATIVE_LAUNCH, true)
                assertEquals(
                    installed,
                    AppLauncher.resolve(TvApp("", brand), listOf(installed), emptyList()).app,
                )
            }
        assertNull(
            AppLauncher.resolve(
                    TvApp("", "YouTube"),
                    listOf(TvApp("kids", "YouTube Kids")),
                    emptyList(),
                )
                .app
        )
    }

    @Test
    fun parsesAppTypeAndSerializesItsLaunchAction() {
        listOf(
                0 to LaunchType.NATIVE_LAUNCH,
                1 to LaunchType.NATIVE_LAUNCH,
                2 to LaunchType.DEEP_LINK,
            )
            .forEach { (code, type) ->
                val a =
                    AppLauncher.parseApp(
                        JSONObject("""{"appId":"regional-id","name":"Shahid","app_type":$code}""")
                    )!!
                assertEquals(type, a.launchType)
                assertTrue(a.typeKnown)
                val data =
                    JSONObject(Protocol.launch(a.id, a.launchType))
                        .getJSONObject("params")
                        .getJSONObject("data")
                assertEquals(type.name, data.getString("action_type"))
                assertEquals(a.id, data.getString("appId"))
            }
        assertFalse(AppLauncher.parseApp(JSONObject("""{"appId":"id"}"""))!!.typeKnown)
    }

    @Test
    fun ambiguityMissingIdsAndUnconfiguredIdsAreExplicitlyUnsupported() {
        val r = TvApp("", "YouTube")
        assertNull(AppLauncher.resolve(r, emptyList(), emptyList()).app)
        assertNull(
            AppLauncher.resolve(
                    r,
                    listOf(TvApp("a", "YouTube"), TvApp("b", "YouTube")),
                    emptyList(),
                )
                .app
        )
        assertNull(
            AppLauncher.resolve(TvApp("not-installed", "YouTube"), emptyList(), emptyList()).app
        )
        assertFalse(AppLauncher.validId("bad\nID"))
        assertNull(AppLauncher.parseApp(JSONObject("""{"name":"Yango Play"}""")))
    }

    @Test
    fun explicitCustomIdIsAllowedAndObservedTypeWinsUnlessOverridden() {
        val custom = TvApp("id", "Shahid")
        assertEquals(
            custom,
            AppLauncher.resolve(TvApp("", "Shahid"), emptyList(), listOf(custom)).app,
        )
        val observed = custom.copy(launchType = LaunchType.NATIVE_LAUNCH, typeKnown = true)
        assertEquals(observed, AppLauncher.resolve(custom, listOf(observed), listOf(custom)).app)
        assertEquals(
            LaunchType.DEEP_LINK,
            AppLauncher.resolve(custom, listOf(observed), listOf(custom.copy(overrideType = true)))
                .app!!
                .launchType,
        )
    }
}
