// ui/components/EnhancedTrackInfo.kt
//
// ТИП: UI Component (Enhanced Track Info)
//
// НАЗНАЧЕНИЕ:
// Компонент для отображения полной информации о треке с метаданными.
// Показывает название, исполнителя, альбом, жанр, год, битрейт и т.д.
//
package com.trec.music.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.data.TrecTrackEnhanced

@Composable
fun EnhancedTrackInfo(
    track: TrecTrackEnhanced,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Название трека
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Исполнитель
            track.getDisplayArtist().let { artist ->
                if (artist != "Неизвестный исполнитель") {
                    Text(
                        text = "Исполнитель: $artist",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Альбом
            track.getDisplayAlbum().let { album ->
                if (album != "Неизвестный альбом") {
                    Text(
                        text = "Альбом: $album",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Ряд с дополнительной информацией
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Жанр
                track.getDisplayGenre().let { genre ->
                    if (genre != "Неизвестный жанр") {
                        Column {
                            Text(
                                text = "Жанр",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                // Год
                track.getDisplayYear().let { year ->
                    if (year != "Неизвестный год") {
                        Column {
                            Text(
                                text = "Год",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                            Text(
                                text = year,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            
            // Техническая информация
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Длительность
                Column {
                    Text(
                        text = "Длительность",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                    Text(
                        text = track.getFormattedDuration(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                
                // Размер файла
                if (track.fileSize > 0) {
                    Column {
                        Text(
                            text = "Размер",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                        Text(
                            text = track.getFormattedFileSize(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            
            // Дополнительная информация (если есть)
            if (track.bitrate != null || track.sampleRate != null || track.composer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Битрейт
                    track.bitrate?.let { bitrate ->
                        Column {
                            Text(
                                text = "Битрейт",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                            Text(
                                text = "${bitrate} kbps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    
                    // Частота дискретизации
                    track.sampleRate?.let { sampleRate ->
                        Column {
                            Text(
                                text = "Частота",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                            Text(
                                text = "${sampleRate} Hz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            
            // Композитор (если есть)
            track.composer?.let { composer ->
                Text(
                    text = "Композитор: $composer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Номер трека (если есть)
            track.trackNumber?.let { trackNumber ->
                Text(
                    text = "Трек №$trackNumber",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
    }
}

// Простая версия для списков
@Composable
fun CompactTrackInfo(
    track: TrecTrackEnhanced,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Название и исполнитель
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        track.getDisplayArtist().let { artist ->
            if (artist != "Неизвестный исполнитель") {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Дополнительная информация в одной строке
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            track.album?.let { album ->
                if (album != "Неизвестный альбом") {
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Text(
                text = track.getFormattedDuration(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
    }
}
