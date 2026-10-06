package de.traewelling.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

class TraewellingApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = {
                        OkHttpClient.Builder()
                            .addInterceptor { chain ->
                                val request = chain.request().newBuilder()
                                    .addHeader("User-Agent", "TraewellingApp/${BuildConfig.VERSION_NAME} (Android)")
                                    .build()
                                chain.proceed(request)
                            }
                            .build()
                    },
                    cacheStrategy = { CacheControlCacheStrategy() }
                ))
            }
            .build()
    }
}
