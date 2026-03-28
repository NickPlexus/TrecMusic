// TrecApplication.kt
//
// ТИП: Root Application Class
//
// НАЗНАЧЕНИЕ:
// Точка входа в приложение. Инициализирует глобальные настройки.
// В данном случае - настраивает библиотеку загрузки изображений Coil.
//
// ОСОБЕННОСТИ (Coil Config):
// 1. AudioCoverFetcher: Подключен кастомный загрузчик, который умеет вытаскивать
//    обложки напрямую из MP3 файлов (через URI), минуя медленный MediaStore или
//    исправляя баги стандартного загрузчика.
// 2. Memory Optimization: Включен allowRgb565(true). Это экономит 50% оперативной памяти
//    на битмапах обложек (так как прозрачность обложкам не нужна).
// 3. Crossfade: Плавное появление картинок.

package com.trec.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.trec.music.utils.AudioCoverFetcher // Убедись, что файл лежит здесь

class TrecApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Дисковый кэш: ускоряет повторные загрузки (и онлайн-обложек, и embedded)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L) // 150 MB
                    .build()
            }
            // Добавляем наш кастомный Fetcher для аудио-обложек
            .components {
                add(AudioCoverFetcher.Factory())
            }
            // Включаем кроссфейд для плавности
            .crossfade(true)
            // Оптимизация памяти: используем RGB_565 для обложек (меньше памяти, качество ок)
            .allowRgb565(true)
            .build()
    }
}
