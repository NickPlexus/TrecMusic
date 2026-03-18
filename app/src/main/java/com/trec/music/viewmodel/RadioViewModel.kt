package com.trec.music.viewmodel

import android.app.Application
import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.trec.music.PrefsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    var player: ExoPlayer? = null
        private set

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: Boolean by _isPlaying

    private val _currentStation = mutableStateOf<RadioStation?>(null)
    val currentStation: RadioStation? by _currentStation

    private val _isLoading = mutableStateOf(false)
    val isLoading: Boolean by _isLoading

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: String? by _errorMessage

    private val _isSimulatingNoise = mutableStateOf(false)
    val isSimulatingNoise: Boolean by _isSimulatingNoise

    private val _unavailableStations = mutableStateOf(setOf<String>())
    val unavailableStations: Set<String> by _unavailableStations

    private var noiseJob: Job? = null

    private val _volume = mutableFloatStateOf(1.0f)
    var volume: Float
        get() = _volume.value
        set(value) {
            _volume.value = value.coerceIn(0f, 1f)
            player?.volume = _volume.value
        }

    private val prefs = PrefsManager(application)

    private val defaultStations = listOf(
        RadioStation("radio_record", "Radio Record", "Electronic / Dance", "https://air.radiorecord.ru:805/rr_320", "https://www.radiorecord.ru/player/img/record-logo.png", "320 kbps", "Танцевальные хиты и EDM", "Россия", "RU"),
        RadioStation("dfm", "DFM", "Dance / Pop", "https://dfm.hostingradio.ru/dfm96.aacp", null, "96 kbps AAC", "Поп‑данс и клубные треки", "Россия", "RU"),
        RadioStation("europa_plus", "Europa Plus", "Pop / Dance", "https://ep128.hostingradio.ru:8064/ep128", null, "128 kbps", "Поп‑музыка и хиты эфира", "Россия", "RU"),
        RadioStation("maximum", "Maximum", "Rock", "https://maximum.hostingradio.ru/maximum128.mp3", null, "128 kbps", "Рок‑классика и новинки", "Россия", "RU"),
        RadioStation("nashe", "Наше Радио", "Rock", "https://nashe1.hostingradio.ru/nashe-128.mp3", null, "128 kbps", "Русский рок", "Россия", "RU"),
        RadioStation("retro_fm", "Ретро FM", "Retro / Disco", "https://retro.hostingradio.ru/retro-128.mp3", null, "128 kbps", "Хиты 80‑90‑х", "Россия", "RU"),
        RadioStation("romantika", "Romantika", "Love Songs", "https://romantika.hostingradio.ru/romantika-128.mp3", null, "128 kbps", "Лиричные и спокойные треки", "Россия", "RU"),
        RadioStation("jazz", "Jazz Radio", "Jazz", "https://jazzradio.hostingradio.ru/jazzradio-128.mp3", null, "128 kbps", "Классический и современный джаз", "Россия", "RU"),
        RadioStation("classic", "Classic FM", "Classical", "https://classicfm.hostingradio.ru/classicfm-128.mp3", null, "128 kbps", "Классическая музыка", "Россия", "RU"),
        RadioStation("lofi", "Lo‑Fi Radio", "Chill / Study", "https://streams.ilovemusic.de/iloveradio17.mp3", null, "128 kbps", "Лоу‑фай для работы и учебы", "Германия", "DE"),
        RadioStation("techno", "Techno Radio", "Techno", "https://streams.ilovemusic.de/iloveradio20.mp3", null, "128 kbps", "Техно и электронный грув", "Германия", "DE"),
        RadioStation("chillout", "Chillout Radio", "Chillout", "https://streams.ilovemusic.de/iloveradio21.mp3", null, "128 kbps", "Расслабляющая электроника", "Германия", "DE"),
        RadioStation("rock_fm", "Rock FM", "Rock", "https://icecast.rockfm.ru/rockfm", null, "128 kbps", "Классический рок", "Россия", "RU"),
        RadioStation("energy", "NRJ", "Top 40", "https://nrj.hostingradio.ru/nrj-128.mp3", null, "128 kbps", "Мировые хиты и чарты", "Россия", "RU"),
        RadioStation("record_deep", "Record Deep", "Deep House", "https://air.radiorecord.ru:805/deep_320", null, "320 kbps", "Глубокий хаус", "Россия", "RU"),
        RadioStation("record_trance", "Record Trance", "Trance", "https://air.radiorecord.ru:805/trance_320", null, "320 kbps", "Транс и прогрессив", "Россия", "RU"),
        RadioStation("record_rock", "Record Rock", "Rock", "https://air.radiorecord.ru:805/rock_320", null, "320 kbps", "Рок‑подборки", "Россия", "RU"),
        RadioStation("record_chill", "Record Chill‑Out", "Chill", "https://air.radiorecord.ru:805/chil_320", null, "320 kbps", "Чилл‑аут и даунтемпо", "Россия", "RU")
    )

    private val _customStations = mutableStateListOf<RadioStation>()
    val customStations: List<RadioStation> get() = _customStations

    private val _hiddenStations = mutableStateOf(setOf<String>())
    val hiddenStations: Set<String> get() = _hiddenStations.value
    val hiddenStationsCount: Int get() = _hiddenStations.value.size

    val stations: List<RadioStation>
        get() = defaultStations.filterNot { hiddenStations.contains(it.id) } + _customStations

    private val _searchQuery = mutableStateOf("")
    val searchQuery: String by _searchQuery

    // Флаг для различения ручной остановки и ошибки
    private var _isManualStop = false

    init {
        initializePlayer()
        loadCustomStations()
        loadHiddenStations()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun initializePlayer() {
        if (player != null) return

        val context = getApplication<Application>()
        try {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(12_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("TrecMusic/2.0")

            player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
                .build()
                .apply {
                    volume = this@RadioViewModel.volume
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _isPlaying.value = isPlaying
                            if (isPlaying) {
                                _isLoading.value = false
                                _isSimulatingNoise.value = false
                                _errorMessage.value = null
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            _isLoading.value = state == Player.STATE_BUFFERING
                            if (state == Player.STATE_READY) {
                                _errorMessage.value = null
                                _isPlaying.value = player?.playWhenReady == true
                            }
                            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                                _isPlaying.value = false
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val station = _currentStation.value
                            _isPlaying.value = false
                            _isLoading.value = false
                            // Если ошибка произошла не из-за ручной остановки, показываем сообщение
                            if (station != null && !_isManualStop) {
                                startNoiseAndFail(station, "Связь с радиостанцией отсутствует")
                            } else {
                                _errorMessage.value = "Ошибка радио: ${error.errorCodeName}"
                            }
                        }
                    })
                }
        } catch (e: Exception) {
            player = null
            _isPlaying.value = false
            _isLoading.value = false
            _errorMessage.value = "Радио временно недоступно на этом устройстве"
        }
    }

    fun playStation(station: RadioStation) {
        viewModelScope.launch {
            try {
                if (player == null) initializePlayer()
                val p = player
                if (p == null) {
                    _errorMessage.value = "Не удалось инициализировать радио‑плеер"
                    return@launch
                }

                // Сбрасываем флаг ручной остановки
                _isManualStop = false

                // Разрешаем повторную попытку для ранее недоступных станций
                if (_unavailableStations.value.contains(station.id)) {
                    _unavailableStations.value = _unavailableStations.value - station.id
                }

                noiseJob?.cancel()
                _isSimulatingNoise.value = false
                _isLoading.value = true
                _errorMessage.value = null
                _currentStation.value = station

                p.stop()
                p.clearMediaItems()

                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(station.url))
                    .setMediaId(station.id)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setArtist(station.genre)
                            .setArtworkUri(station.iconUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()

                p.setMediaItem(mediaItem)
                p.prepare()
                p.playWhenReady = true
                p.play()
                p.volume = volume
            } catch (e: Exception) {
                _isLoading.value = false
                _isPlaying.value = false
                startNoiseAndFail(station, "Не удалось подключиться к станции")
            }
        }
    }

    fun togglePlayPause() {
        val p = player
        if (p == null) {
            _currentStation.value?.let { playStation(it) }
            return
        }

        if (p.playWhenReady || p.isPlaying) {
            pausePlayback()
            return
        }

        val station = _currentStation.value ?: return
        val currentId = p.currentMediaItem?.mediaId
        if (p.mediaItemCount == 0 || currentId != station.id) {
            playStation(station)
        } else {
            _isManualStop = false
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            }
            p.play()
        }
    }

    private fun pausePlayback() {
        _isManualStop = true
        _isPlaying.value = false
        _isLoading.value = false
        noiseJob?.cancel()
        _isSimulatingNoise.value = false
        player?.apply {
            playWhenReady = false
            pause()
            if (playbackState != Player.STATE_IDLE) {
                stop()
            }
        }
    }

    private fun stopPlayback() {
        _isManualStop = true
        _isPlaying.value = false
        _isLoading.value = false
        noiseJob?.cancel()
        _isSimulatingNoise.value = false
        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (_: Exception) {
        }
    }

    @Deprecated("Используйте stopPlayback() для временной остановки")
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _isPlaying.value = false
        _isLoading.value = false
        _currentStation.value = null
    }

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun isStationUnavailable(stationId: String): Boolean = _unavailableStations.value.contains(stationId)

    private fun startNoiseAndFail(station: RadioStation, message: String) {
        noiseJob?.cancel()
        _isLoading.value = false
        _isPlaying.value = false
        _isSimulatingNoise.value = true
        noiseJob = viewModelScope.launch {
            delay(2500)
            _isSimulatingNoise.value = false
            _unavailableStations.value = _unavailableStations.value + station.id
            _errorMessage.value = message
        }
    }

    private fun loadCustomStations() {
        val raw = prefs.getCustomRadioStationsJson() ?: return
        val list = mutableListOf<RadioStation>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val station = stationFromJson(obj) ?: continue
                list.add(station)
            }
        } catch (_: Exception) {
        }
        _customStations.clear()
        _customStations.addAll(list)
    }

    private fun saveCustomStations() {
        val arr = JSONArray()
        _customStations.forEach { arr.put(stationToJson(it)) }
        prefs.saveCustomRadioStations(arr.toString())
    }

    private fun loadHiddenStations() {
        _hiddenStations.value = prefs.getHiddenRadioStations().toSet()
    }

    private fun saveHiddenStations() {
        prefs.saveHiddenRadioStations(_hiddenStations.value)
    }

    fun restoreHiddenStations() {
        _hiddenStations.value = emptySet()
        saveHiddenStations()
    }

    fun addCustomStation(
        name: String,
        url: String,
        genre: String,
        iconUrl: String?,
        bitrate: String,
        description: String,
        country: String,
        language: String
    ) {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isBlank() || cleanUrl.isBlank()) return

        val id = "custom_${System.currentTimeMillis()}"
        val station = RadioStation(
            id = id,
            name = cleanName,
            genre = genre.trim().ifBlank { "Custom" },
            url = cleanUrl,
            iconUrl = iconUrl?.trim()?.takeIf { it.isNotBlank() },
            bitrate = bitrate.trim().ifBlank { "Stream" },
            description = description.trim().ifBlank { "Пользовательская станция" },
            country = country.trim().ifBlank { "Custom" },
            language = language.trim().ifBlank { "—" }
        )

        _customStations.add(0, station)
        saveCustomStations()
    }

    fun deleteStation(station: RadioStation) {
        val wasCustom = _customStations.any { it.id == station.id }
        if (wasCustom) {
            _customStations.removeAll { it.id == station.id }
            saveCustomStations()
        } else {
            _hiddenStations.value = _hiddenStations.value + station.id
            saveHiddenStations()
        }

        if (_currentStation.value?.id == station.id) {
            stopPlayback()
            _currentStation.value = null
        }
    }

    private fun stationToJson(station: RadioStation): JSONObject {
        return JSONObject().apply {
            put("id", station.id)
            put("name", station.name)
            put("genre", station.genre)
            put("url", station.url)
            put("iconUrl", station.iconUrl ?: JSONObject.NULL)
            put("bitrate", station.bitrate)
            put("description", station.description)
            put("country", station.country)
            put("language", station.language)
        }
    }

    private fun stationFromJson(obj: JSONObject): RadioStation? {
        val id = obj.optString("id")
        val name = obj.optString("name")
        val url = obj.optString("url")
        if (id.isBlank() || name.isBlank() || url.isBlank()) return null
        val iconUrl = if (obj.isNull("iconUrl")) null else obj.optString("iconUrl")
        return RadioStation(
            id = id,
            name = name,
            genre = obj.optString("genre", "Custom"),
            url = url,
            iconUrl = iconUrl,
            bitrate = obj.optString("bitrate", "Stream"),
            description = obj.optString("description", "Пользовательская станция"),
            country = obj.optString("country", "Custom"),
            language = obj.optString("language", "—")
        )
    }

    fun getFilteredStations(): List<RadioStation> {
        val query = _searchQuery.value.lowercase()
        return if (query.isEmpty()) {
            stations
        } else {
            stations.filter {
                it.name.lowercase().contains(query) ||
                        it.genre.lowercase().contains(query) ||
                        it.description.lowercase().contains(query)
            }
        }
    }

    override fun onCleared() {
        noiseJob?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}

data class RadioStation(
    val id: String,
    val name: String,
    val genre: String,
    val url: String,
    val iconUrl: String?,
    val bitrate: String,
    val description: String,
    val country: String,
    val language: String
)
