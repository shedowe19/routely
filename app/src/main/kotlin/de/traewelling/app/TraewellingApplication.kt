package de.traewelling.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class TraewellingApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("User-Agent", "TraewellingApp/${BuildConfig.VERSION_NAME} (Android)")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            }
            .build()
    }
}
