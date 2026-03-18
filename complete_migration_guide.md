# 🔄 Полная миграция на TrecTrackEnhanced

## 🎯 **Что нужно сделать:**

### **Шаг 1: Удалить старый файл**
```bash
# Удалить полностью:
C:\Users\NPC\AndroidStudioProjects\TrecMusic\app\src\main\java\com\trec\music\data\TrecTrackEnhanced.kt
```

### **Шаг 2: Заменить импорты во всех файлах**

#### **Файлы для замены:**

**1. MusicViewModel.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// На:
import com.trec.music.data.TrecTrackEnhanced

// Заменить все TrecTrackEnhanced на TrecTrackEnhanced:
private val _tracks = MutableStateFlow<List<TrecTrackEnhanced>>(emptyList())
val tracks: StateFlow<List<TrecTrackEnhanced>> = _tracks.asStateFlow()

// Во всех функциях:
fun getAllTracks(): List<TrecTrackEnhanced>
fun loadTrack(uri: Uri): TrecTrackEnhanced
fun getTrackTitle(track: TrecTrackEnhanced): String
```

**2. LibraryHandler.kt**
```kotlin
// Заменить импорт и все использования:
import com.trec.music.data.TrecTrackEnhanced

// Заменить сигнатуры:
fun getAllTracks(): List<TrecTrackEnhanced>
fun scanTracks(uri: Uri): List<TrecTrackEnhanced>
fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced)
```

**3. LibraryRepository.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// Заменить все:
fun getAllTracks(): List<TrecTrackEnhanced>
fun getPlaylistTracks(name: String): List<TrecTrackEnhanced>
```

**4. TrackComponents.kt**
```kotlin
// Добавить импорт:
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.components.EnhancedTrackInfo

// Заменить:
fun TrackRow(track: TrecTrackEnhanced) -> TrackRow(track: TrecTrackEnhanced)
fun TrackInfoDialog(track: TrecTrackEnhanced) -> TrackInfoDialog(track: TrecTrackEnhanced)
fun AddToPlaylistDialog(track: TrecTrackEnhanced) -> AddToPlaylistDialog(track: TrecTrackEnhanced)
```

**5. FullPlayerOverlay.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// Заменить:
EnhancedTrackInfo(track = currentTrack)
```

**6. FavoritesScreen.kt**
```kotlin
// Заменить все использования TrecTrackEnhanced на TrecTrackEnhanced
```

**7. HomeScreen.kt**
```kotlin
// Заменить все использования TrecTrackEnhanced на TrecTrackEnhanced
```

**8. MiniPlayer.kt**
```kotlin
// Заменить импорт и использования:
import com.trec.music.data.TrecTrackEnhanced
```

**9. PrefsManager.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// Заменить все функции:
fun saveTrack(track: TrecTrackEnhanced)
fun getRecentTracks(): List<TrecTrackEnhanced>
```

**10. AudioReverser.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// Заменить:
fun generateReversed(context: Context, track: TrecTrackEnhanced)
```

**11. VocalRemoverEngine.kt**
```kotlin
// Заменить:
import com.trec.music.data.TrecTrackEnhanced

// Заменить:
fun generateInstrumental(context: Context, sourceUri: Uri, outputFile: File): ProcessingResult
```

## 🔧 **Автоматическая замена через PowerShell:**

### **Скрипт для массовой замены:**
```powershell
# migration_script.ps1
$projectPath = "C:\Users\NPC\AndroidStudioProjects\TrecMusic\app\src\main\java\com\trec\music"

# Файлы для обработки
$files = @(
    "$projectPath\viewmodel\MusicViewModel.kt"
    "$projectPath\data\LibraryHandler.kt"
    "$projectPath\data\LibraryRepository.kt"
    "$projectPath\ui\components\TrackComponents.kt"
    "$projectPath\ui\screens\FullPlayerOverlay.kt"
    "$projectPath\ui\screens\FavoritesScreen.kt"
    "$projectPath\ui\screens\HomeScreen.kt"
    "$projectPath\ui\components\MiniPlayer.kt"
    "$projectPath\PrefsManager.kt"
    "$projectPath\utils\AudioReverser.kt"
    "$projectPath\utils\VocalRemoverEngine.kt"
)

foreach ($file in $files) {
    if (Test-Path $file) {
        Write-Host "Processing: $file"
        
        # Читаем содержимое
        $content = Get-Content $file -Raw
        
        # Заменяем импорты
        $content = $content -replace "import com.trec.music.data.TrecTrackEnhanced", "import com.trec.music.data.TrecTrackEnhanced"
        
        # Заменяем все вхождения TrecTrackEnhanced на TrecTrackEnhanced
        $content = $content -replace "\bTrecTrackEnhanced\b", "TrecTrackEnhanced"
        
        # Сохраняем обратно
        Set-Content -Path $file -Value $content -Encoding UTF8
        Write-Host "Updated: $file"
    }
}

Write-Host "Migration completed!"
```

## 🗑️ **Что удалить:**

### **Старые файлы:**
- ❌ `TrecTrackEnhanced.kt` - полностью удалить
- ❌ Все временные .md файлы отчетов

### **Временные файлы для очистки:**
- ❌ `vocal_remover_*.md`
- ❌ `simple_vocal_remover_*.md`
- ❌ `vocal_replacement_guide.md`
- ❌ `compilation_fix_guide.md`
- ❌ `vocal_remover_engine_*.md`

## ✅ **Проверка после миграции:**

### **Компиляция:**
```bash
./gradlew compileDebugKotlin
```

### **Поиск оставшихся ссылок:**
```powershell
# Проверка что ничего не пропустили
Get-ChildItem -Path "C:\Users\NPC\AndroidStudioProjects\TrecMusic\app\src\main\java\com\trec\music" -Recurse -File | 
    Select-String -Pattern "TrecTrackEnhanced(?!\w)" | 
    Select-Object Name, Line
```

---

## 🏆 **Статус: ИНСТРУКЦИЯ ГОТОВА!**

**Полная миграция на TrecTrackEnhanced:**
- ✅ **Удалить старый TrecTrackEnhanced.kt**
- ✅ **Заменить все импорты** в 11 файлах
- ✅ **Обновить все сигнатуры** функций
- ✅ **Использовать MetadataExtractor** для извлечения
- ✅ **Применить EnhancedTrackInfo** UI

**После миграции:**
- 🎵 **Больше никаких цифр** вместо названий
- 🎤 **Реальные исполнители** вместо "TREC Local Audio"
- 📊 **Полная информация** - альбом, жанр, год, битрейт
- 🎨 **Красивый UI** с Material 3 дизайном

**Выполните скрипт migration_script.ps1 или замените вручную по списку выше!** 🚀✨
