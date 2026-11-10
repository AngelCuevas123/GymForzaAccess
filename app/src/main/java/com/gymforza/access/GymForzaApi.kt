package com.gymforza.access

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

data class QrRequest(
    val token: String
)

data class BackendUser(
    val id: String?,
    val role: String?,
    @SerializedName("full_name") val fullName: String?,
    val email: String?
)

data class MeResponse(
    val message: String?,
    val user: BackendUser?
)

data class MemberInfo(
    @SerializedName("client_id") val clientId: String?,
    @SerializedName("full_name") val fullName: String?,
    @SerializedName("photo_url") val photoUrl: String?,
    @SerializedName("plan_name") val planName: String?,
    @SerializedName("end_date") val endDate: String?
)

data class QrValidationResponse(
    val authorized: Boolean = false,
    val reason: String? = null,
    val message: String? = null,
    val member: MemberInfo? = null
)

interface GymForzaApi {
    @GET("auth/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): Response<MeResponse>

    @POST("qr/validate")
    suspend fun validateQr(
        @Header("Authorization") authorization: String,
        @Body request: QrRequest
    ): Response<QrValidationResponse>
}

object ApiProvider {
    val api: GymForzaApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://gymforza.onrender.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GymForzaApi::class.java)
    }
}

/** Extrae el mensaje JSON que Express devuelve en respuestas HTTP no exitosas. */
fun <T> Response<T>.backendMessage(fallback: String): String {
    return runCatching {
        JSONObject(errorBody()?.string().orEmpty())
            .optString("message")
            .takeIf { it.isNotBlank() }
    }.getOrNull() ?: fallback
}
