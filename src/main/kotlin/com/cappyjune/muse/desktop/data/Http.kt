package com.cappyjune.muse.desktop.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    val USER_AGENT = "muSE-Desktop/" + (System.getProperty("muse.version") ?: "dev")

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("User-Agent", USER_AGENT)
            // Streams and artwork from the signed-in Jellyfin server carry the token as a header.
            if (req.header("Authorization") == null && Jellyfin.owns(req.url)) b.header("Authorization", Jellyfin.authHeader())
            chain.proceed(b.build())
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            r.body.string()
        }
}
