package com.aeonps1.tv

import android.view.Surface

/** JNI bridge to the native libretro frontend (aeonps1_bridge.c). */
object EmulatorBridge {
    init {
        System.loadLibrary("aeonps1_bridge")
    }

    /** corePath = absolute path to libpcsx_rearmed_libretro_android.so */
    external fun nativeInit(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun nativeLoadGame(path: String): Boolean
    external fun nativeRunFrame()
    external fun nativeSetSurface(surface: Surface?)
    external fun nativeSetButtons(port: Int, mask: Int)
    external fun nativeSetAnalog(port: Int, x: Int, y: Int)
    external fun nativeSetScreenMode(mode: Int) // 0 = 4:3, 1 = stretch
    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean
    external fun nativeReset(): Boolean
    /** Drains up to maxShorts stereo int16 samples into [out]; returns count drained. */
    external fun nativeGetAudioSamples(out: ShortArray, maxShorts: Int): Int
    external fun nativeGetFps(): Float
    external fun nativeGetTargetFps(): Float
    external fun nativeFlushSaveRam()
    external fun nativeShutdown()
    /** The bridge's recent diagnostic log (env refusals, core log lines). */
    external fun nativeGetLogTail(): String
    /** The last message the core sent via the message interface, if any. */
    external fun nativeGetCoreMessage(): String
}
