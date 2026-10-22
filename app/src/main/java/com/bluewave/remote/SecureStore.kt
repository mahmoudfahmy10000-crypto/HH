package com.bluewave.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class Tv(
    val ip: String,
    val name: String = "Samsung TV",
    val mac: String = "",
    val secure: Boolean = true,
)

data class TvApp(val id: String, val name: String)

interface PairingStore {
    fun token(ip: String): String?

    fun token(ip: String, value: String)

    fun pin(ip: String): String?

    fun save(tv: Tv)
}

class SecureStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("tv-pairing", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "tv-pairing",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    override fun token(ip: String): String? =
        prefs.getString("token:$ip", null)?.let { encoded ->
            runCatching {
                    val b = Base64.decode(encoded, Base64.NO_WRAP)
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(
                            Cipher.DECRYPT_MODE,
                            key(),
                            GCMParameterSpec(128, b.copyOfRange(0, 12)),
                        )
                        String(doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
                    }
                }
                .getOrNull()
        }

    override fun token(ip: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs
            .edit()
            .putString(
                "token:$ip",
                Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP),
            )
            .apply()
    }

    override fun pin(ip: String): String? = prefs.getString("pin:$ip", null)

    fun pin(ip: String, value: String) {
        prefs.edit().putString("pin:$ip", value).apply()
    }

    fun saved(): List<Tv> =
        runCatching {
                val a = JSONArray(prefs.getString("tvs", "[]"))
                (0 until a.length()).map {
                    val t = a.getJSONObject(it)
                    Tv(
                        t.getString("ip"),
                        t.getString("name"),
                        t.optString("mac"),
                        t.optBoolean("secure", true),
                    )
                }
            }
            .getOrDefault(emptyList())

    override fun save(tv: Tv) {
        write(listOf(tv) + saved().filterNot { it.ip == tv.ip })
        prefs.edit().putString("last", tv.ip).apply()
    }

    private fun write(tvs: List<Tv>) {
        prefs
            .edit()
            .putString(
                "tvs",
                JSONArray()
                    .apply {
                        tvs.forEach {
                            put(
                                JSONObject()
                                    .put("ip", it.ip)
                                    .put("name", it.name)
                                    .put("mac", it.mac)
                                    .put("secure", it.secure)
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
    }

    fun forget(ip: String) {
        write(saved().filterNot { it.ip == ip })
        prefs.edit().remove("token:$ip").remove("pin:$ip").apply()
        if (prefs.getString("last", null) == ip) prefs.edit().remove("last").apply()
    }

    fun last(): Tv? = saved().find { it.ip == prefs.getString("last", null) }

    fun shortcuts(): List<TvApp> =
        runCatching {
                val a = JSONArray(prefs.getString("shortcuts", "[]"))
                (0 until a.length()).map {
                    TvApp(
                        a.getJSONObject(it).getString("id"),
                        a.getJSONObject(it).getString("name"),
                    )
                }
            }
            .getOrDefault(emptyList())

    fun shortcuts(apps: List<TvApp>) {
        prefs
            .edit()
            .putString(
                "shortcuts",
                JSONArray()
                    .apply {
                        apps.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                    }
                    .toString(),
            )
            .apply()
    }
}
