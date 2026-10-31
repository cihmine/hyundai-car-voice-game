package com.cihmine.carvoicegame.data

import android.util.AtomicFile
import com.cihmine.carvoicegame.core.save.SaveCodec
import com.cihmine.carvoicegame.core.save.SaveData
import com.cihmine.carvoicegame.core.save.SaveStore
import java.io.File

/** 앱 전용 폴더의 JSON 파일. 쓰다가 꺼져도 이전 내용이 남도록 AtomicFile 로 쓴다. */
class FileSaveStore(dir: File) : SaveStore {
    private val file = AtomicFile(File(dir, "save.json"))

    override fun load(): SaveData? = runCatching {
        SaveCodec.decode(file.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    override fun save(data: SaveData) {
        val out = file.startWrite()
        try {
            out.write(SaveCodec.encode(data).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
