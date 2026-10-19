package com.aktv.app

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource

/** Native requests use the current source's metadata, never a hard-coded list of player profiles. */
object Streams {
    const val CHROME = TgHttp.USER_AGENT

    fun factory(stream: TgStream): DataSource.Factory =
        OkHttpDataSource.Factory(StreamHttp.client(stream)).setUserAgent(CHROME)
}
