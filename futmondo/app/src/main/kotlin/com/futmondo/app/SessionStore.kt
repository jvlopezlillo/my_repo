package com.futmondo.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.futmondo.core.Session
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda SOLO el token de sesión (y los ids de liga/equipo), cifrado con una clave AES-GCM del
 * Android Keystore. La contraseña nunca se almacena.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(s: Session) {
        val json = JSONObject()
            .put("t", s.token).put("u", s.userId).put("c", s.championshipId)
            .put("ut", s.userteamId).put("n", s.teamName).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString("data", enc).apply()
    }

    fun load(): Session? = try {
        val parts = prefs.getString("data", null)?.split(":") ?: return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val o = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
        Session(o.getString("t"), o.getString("u"), o.getString("c"), o.getString("ut"), o.optString("n"))
    } catch (e: Exception) {
        null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ALIAS = "futmondo_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
