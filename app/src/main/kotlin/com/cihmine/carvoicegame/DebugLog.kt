package com.cihmine.carvoicegame

import android.util.Log

/** 진행 확인용 로그. 인식한 말이 담기므로 release 빌드에서는 남기지 않는다. */
object DebugLog {
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }
}
