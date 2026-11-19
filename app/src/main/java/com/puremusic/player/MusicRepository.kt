package com.puremusic.player

import android.content.Context
import android.provider.MediaStore
import java.io.File

class MusicRepository(private val context: Context) {

    fun loadAllSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: ""
                    val parentFile = File(path).parentFile
                    val folderName = parentFile?.name ?: "默认"

                    val song = Song(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "未知歌曲",
                        artist = cursor.getString(artistCol) ?: "未知歌手",
                        album = cursor.getString(albumCol) ?: "未知专辑",
                        duration = cursor.getLong(durationCol),
                        path = path,
                        folder = folderName
                    )
                    list.add(song)
                }
            }
        } catch (_: Exception) {}

        // 载入持久化的喜好和播放次数
        BackupManager.loadBackup(context, list)
        return list
    }

    fun filterByIntent(songs: List<Song>, query: String): List<Song> {
        if (query.isBlank()) return songs
        val q = query.trim().lowercase()

        return when {
            q == "喜欢" || q == "收藏" || q == "fav" -> songs.filter { it.isFavorite }
            q == "最常听" || q == "热听" -> songs.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
            else -> songs.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.folder.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
    }
}
