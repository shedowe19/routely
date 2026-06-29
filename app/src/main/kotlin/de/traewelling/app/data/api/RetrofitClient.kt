package de.traewelling.app.data.api

import de.traewelling.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val USER_AGENT = "TraewellingApp/${BuildConfig.VERSION_NAME} (Android)"

    fun createApiService(baseUrl: String, accessToken: String): TraewellingApiService {
        val client = buildOkHttpClient(accessToken)
        return Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TraewellingApiService::class.java)
    }

    fun createOAuthService(baseUrl: String): OAuthApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor())
            .build()

        return Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OAuthApiService::class.java)
    }

    private fun buildOkHttpClient(accessToken: String): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor())
            .build()
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
}
