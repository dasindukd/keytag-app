package com.keytag.app

/** Small shared state so the screen can show what the background service is doing. */
object Status {
    @Volatile var connection: String = "Not started"
    @Volatile var nowPlaying: String = "-"
}
