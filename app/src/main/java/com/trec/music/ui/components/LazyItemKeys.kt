package com.trec.music.ui.components

import com.trec.music.data.TrecTrackEnhanced

/**
 * Compose Lazy списки требуют уникальные key для каждого элемента.
 * В реальности (особенно при быстрых обновлениях состояния/сканированиях)
 * один и тот же Uri может кратковременно попасть в список дважды, что приводит к:
 * IllegalArgumentException: Key "..." was already used.
 *
 * Этот helper генерирует *стабильные* и *уникальные* ключи:
 * - для уникальных треков ключ = Uri (как и ожидается)
 * - для дублей добавляется суффикс "#N"
 */
fun stableTrackKeys(tracks: List<TrecTrackEnhanced>): List<String> {
    val counters = HashMap<String, Int>(tracks.size)
    return tracks.map { track ->
        val base = track.uri.toString()
        val n = counters[base] ?: 0
        counters[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

