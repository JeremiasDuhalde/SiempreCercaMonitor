package com.siemprecerca.monitor.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gestiona la autenticacion con el servidor.
 * Hace login automatico con credenciales precargadas y renueva el JWT
 * cuando esta por expirar.
 */
class AuthManager(private val prefs: Preferences) {

    companion object {
        private const val TAG = "AuthManager"
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class LoginRequest(val email: String, val password: String)
    data class LoginResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("token_type") val tokenType: String
    )

    data class ClientResponse(
        val id: Int,
        @SerializedName("full_name") val fullName: String?,
        val name: String?,
        @SerializedName("first_name") val firstName: String?,
        @SerializedName("last_name") val lastName: String?
    ) {
        fun displayName(): String = fullName ?: name ?: "${firstName ?: ""} ${lastName ?: ""}".trim()
    }

    /**
     * Hace login al servidor y guarda el JWT.
     * Se llama automaticamente al iniciar y cada vez que un request falla con 401.
     */
    fun login(callback: ((Boolean) -> Unit)? = null) {
        val serverConfig = prefs.getServerConfig()
        val url = "${serverConfig.baseUrl}/api/auth/login"

        val body = gson.toJson(LoginRequest(Config.LOGIN_EMAIL, Config.LOGIN_PASSWORD))

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error en login: ${e.message}")
                callback?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val resp = gson.fromJson(it.body?.string(), LoginResponse::class.java)
                        prefs.saveServerConfig(serverConfig.copy(authToken = resp.accessToken))
                        Log.i(TAG, "Login exitoso, JWT renovado")
                        callback?.invoke(true)
                    } else {
                        Log.e(TAG, "Login fallido: ${it.code}")
                        callback?.invoke(false)
                    }
                }
            }
        })
    }

    /**
     * Obtiene los datos de un paciente por ID.
     * Usado durante el setup para autocompletar el nombre.
     */
    fun fetchClient(clientId: Int, callback: (ClientResponse?) -> Unit) {
        ensureToken {
            val serverConfig = prefs.getServerConfig()
            val url = "${serverConfig.baseUrl}/api/clients/$clientId"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer ${serverConfig.authToken}")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Error obteniendo paciente: ${e.message}")
                    callback(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            val client = gson.fromJson(it.body?.string(), ClientResponse::class.java)
                            callback(client)
                        } else {
                            Log.e(TAG, "Error obteniendo paciente: ${it.code}")
                            callback(null)
                        }
                    }
                }
            })
        }
    }

    /**
     * Registra el dispositivo FLIC en el backend, vinculandolo al cliente.
     * Llama a POST /api/clients/assign-device.
     */
    fun registerDevice(clientId: Int, buttonSerial: String, callback: ((Boolean) -> Unit)? = null) {
        ensureToken {
            val serverConfig = prefs.getServerConfig()
            val url = "${serverConfig.baseUrl}/api/clients/assign-device"

            val body = gson.toJson(mapOf(
                "client_id" to clientId,
                "button_serial" to buttonSerial
            ))

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serverConfig.authToken}")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Error registrando dispositivo: ${e.message}")
                    callback?.invoke(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.i(TAG, "Dispositivo registrado OK: serial=$buttonSerial clientId=$clientId")
                            callback?.invoke(true)
                        } else {
                            Log.e(TAG, "Error registrando dispositivo: ${it.code} ${it.body?.string()}")
                            // 409 = ya tiene device vinculado, no es error critico
                            callback?.invoke(it.code == 409)
                        }
                    }
                }
            })
        }
    }

    /**
     * Asegura que haya un token valido antes de hacer un request.
     * Si no hay token, hace login primero.
     */
    private fun ensureToken(then: () -> Unit) {
        val token = prefs.getServerConfig().authToken
        if (token.isBlank()) {
            login { success ->
                if (success) then()
            }
        } else {
            then()
        }
    }
}
