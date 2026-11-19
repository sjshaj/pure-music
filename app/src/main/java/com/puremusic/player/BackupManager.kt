package com.puremusic.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BackupManager {
    private const val FILE_NAME = "pure_music_backup.json"

    fun getBackupFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun saveBackup(context: Context, songs: List<Song>) {
        try {
            val array = JSONArray()
            songs.filter { it.isFavorite || it.playCount > 0 }.forEach { song ->
                val obj = JSONObject().apply {
                    put("title", song.title)
                    put("artist", song.artist)
                    put("playCount", song.playCount)
                    put("isFavorite", song.isFavorite)
                }
                array.put(obj)
            }
            getBackupFile(context).writeText(array.toString(2))
        } catch (_: Exception) {}
    }

    fun loadBackup(context: Context, songs: List<Song>) {
        val file = getBackupFile(context)
        if (!file.exists()) return

        try {
            val content = file.readText()
            val array = JSONArray(content)
            val map = mutableMapOf<String, Pair<Int, Boolean>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val key = "${obj.optString("title")}|${obj.optString("artist")}"
                map[key] = Pair(obj.optInt("playCount", 0), obj.optBoolean("isFavorite", false))
            }

            songs.forEach { song ->
                val key = "${song.title}|${song.artist}"
                map[key]?.let { (count, fav) ->
                    song.playCount = count
                    song.isFavorite = fav
                }
            }
        } catch (_: Exception) {}
    }
}
