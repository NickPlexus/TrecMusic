# 🚀 Как запустить миграцию в Android Studio

## 📋 **Инструкция по запуску:**

### **Способ 1: Terminal в Android Studio (рекомендуется)**

1. **Откройте Terminal в Android Studio**
   - Нажмите `Alt + F12` или
   - View → Tool Windows → Terminal

2. **Перейдите в папку проекта:**
   ```bash
   cd C:\Users\NPC\AndroidStudioProjects\TrecMusic
   ```

3. **Запустите PowerShell:**
   ```bash
   powershell -ExecutionPolicy Bypass -File "simple_migration.ps1"
   ```

### **Способ 2: Windows PowerShell**

1. **Откройте PowerShell** (Win + X → Windows PowerShell)

2. **Перейдите в папку:**
   ```powershell
   cd "C:\Users\NPC\AndroidStudioProjects\TrecMusic"
   ```

3. **Запустите скрипт:**
   ```powershell
   .\simple_migration.ps1
   ```

### **Способ 3: Ручная замена (если PowerShell не работает)**

#### **Что заменить в каждом файле:**

**1. MusicViewModel.kt:**
```kotlin
// Заменить импорт:
import com.trec.music.data.TrecTrackEnhanced

// Заменить все:
TrecTrackEnhanced → TrecTrackEnhanced

// Пример:
private val _tracks = MutableStateFlow<List<TrecTrackEnhanced>>(emptyList())
```

**2. LibraryHandler.kt:**
```kotlin
// Заменить импорт:
import com.trec.music.data.TrecTrackEnhanced

// Заменить все сигнатуры:
fun getAllTracks(): List<TrecTrackEnhanced>
fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced)
```

**3. TrackComponents.kt:**
```kotlin
// Добавить импорты:
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.components.EnhancedTrackInfo

// Заменить:
fun TrackRow(track: TrecTrackEnhanced) → fun TrackRow(track: TrecTrackEnhanced)
fun TrackInfoDialog(track: TrecTrackEnhanced) → fun TrackInfoDialog(track: TrecTrackEnhanced)
```

**4. FullPlayerOverlay.kt:**
```kotlin
// Добавить импорт:
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.components.EnhancedTrackInfo

// Заменить:
EnhancedTrackInfo(track = currentTrack)
```

## 🔧 **Быстрая замена в Android Studio:**

### **Используйте Replace in Path:**
1. **Ctrl + Shift + R** (Replace in Path)
2. **Search:** `TrecTrackEnhanced`
3. **Replace:** `TrecTrackEnhanced`
4. **Scope:** Whole project
5. **Нажмите Replace All**

### **Замените импорты отдельно:**
1. **Ctrl + Shift + R**
2. **Search:** `import com.trec.music.data.TrecTrackEnhanced`
3. **Replace:** `import com.trec.music.data.TrecTrackEnhanced`
4. **Scope:** Whole project
5. **Нажмите Replace All**

## ✅ **После миграции:**

### **Проверьте компиляцию:**
```bash
./gradlew compileDebugKotlin
```

### **Проверьте что все заменено:**
1. **Ctrl + Shift + F**
2. **Search:** `TrecTrackEnhanced`
3. **Убедитесь что нет оставшихся ссылок**

---

## 🎯 **Что должно получиться:**

### **До миграции:**
```kotlin
import com.trec.music.data.TrecTrackEnhanced
private val _tracks = MutableStateFlow<List<TrecTrackEnhanced>>(emptyList())
fun getAllTracks(): List<TrecTrackEnhanced>
```

### **После миграции:**
```kotlin
import com.trec.music.data.TrecTrackEnhanced
private val _tracks = MutableStateFlow<List<TrecTrackEnhanced>>(emptyList())
fun getAllTracks(): List<TrecTrackEnhanced>
```

---

## 🏆 **Статус: ИНСТРУКЦИЯ ГОТОВА!**

**Выберите один из способов:**
- ✅ **Terminal в Android Studio** (рекомендуется)
- ✅ **Windows PowerShell** (альтернатива)
- ✅ **Ручная замена** (если PowerShell не работает)

**После успешной миграции:**
- 🎵 **Больше никаких цифр** вместо названий
- 🎤 **Реальные исполнители** вместо "TREC Local Audio"
- 📊 **Полная информация** о треках
- 🎨 **Красивый UI** с Material 3

**Начинайте миграцию!** 🚀✨
