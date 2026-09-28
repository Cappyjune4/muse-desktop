package com.cappyjune.muse.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** The parts of libmpv's C API muSE uses (client.h). */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_terminate_destroy(ctx: Pointer)

    /** libc's setlocale: mpv refuses to start unless numbers are formatted the "C" way. */
    interface LibC : Library { fun setlocale(category: Int, locale: String): String? }

    companion object {
        val lib: LibMpv by lazy { Native.load("mpv", LibMpv::class.java) }
    }
}

/**
 * One mpv for sound only: no window, no video. It shows up in GNOME's sound settings (and
 * PipeWire's mixers) as "muSE".
 */
class MpvPlayer {
    private val mpv = LibMpv.lib
    private val ctx: Pointer = run {
        Native.load("c", LibMpv.LibC::class.java).setlocale(1 /* LC_NUMERIC */, "C")
        mpv.mpv_create() ?: error("libmpv couldn't start")
    }

    init {
        opt("vo", "null"); opt("video", "no"); opt("audio-display", "no")
        opt("terminal", "no"); opt("idle", "yes")
        // The last song stays loaded when it ends (so its position is known); songs before it
        // run into the next one with no gap.
        opt("keep-open", "yes"); opt("gapless-audio", "weak")
        opt("audio-client-name", "muSE")
        opt("network-timeout", "15")
        // Radio stations drop out; a few seconds of buffer rides over most hiccups.
        opt("cache", "yes"); opt("cache-secs", "10")
        check(mpv.mpv_initialize(ctx) >= 0) { "mpv_initialize failed" }
    }

    private fun opt(name: String, value: String) { mpv.mpv_set_option_string(ctx, name, value) }

    fun command(vararg args: String) { mpv.mpv_command(ctx, arrayOf(*args, null)) }
    fun set(name: String, value: String) { mpv.mpv_set_property_string(ctx, name, value) }
    fun get(name: String): String? = mpv.mpv_get_property_string(ctx, name)?.let { p -> p.getString(0).also { mpv.mpv_free(p) } }

    fun close() = mpv.mpv_terminate_destroy(ctx)
}
