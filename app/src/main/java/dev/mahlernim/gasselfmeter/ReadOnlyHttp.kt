package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun readOnlyHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()
