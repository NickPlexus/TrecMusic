# 🚨 СРОЧНОЕ ИСПРАВЛЕНИЕ ОШИБОК КОМПИЛЯЦИИ

## 🎯 **Проблема:**

1. **Неправильное название класса** - `TrecTrackEnhanced` вместо `TrecTrackEnhanced`
2. **Отсутствие импортов** - много файлов не находят `TrecTrackEnhanced`
3. **Ошибки типов** - проблемы с `uri`, `title`, `artist` полями
4. **Отсутствие TAG** - в DspHandler.kt нет импорта Log
5. **Проблемы с MetadataExtractor** - ошибки типов в конструкторе

## 🔧 **Что нужно исправить:**

### **1. Исправить название класса:**
```kotlin
// Было (неправильно):
data class TrecTrackEnhanced(

// Стало (правильно):
data class TrecTrackEnhanced(
```

### **2. Исправить ошибки типов:**
- `year` должен быть `Int?` а не `String`
- Исправить параметры конструктора в MetadataExtractor
- Добавить недостающие импорты

### **3. Добавить импорты:**
```kotlin
import android.util.Log
import com.trec.music.data.TrecTrackEnhanced
```

### **4. Исправить методы доступа:**
```kotlin
// Было:
track.uri  // Ошибка - нет такого поля

// Стало:  
track.uri  // Правильно - есть в TrecTrackEnhanced
```

---

## ⚠️ **ВАЖНО:**

**Пользователь случайно заменил `TrecTrack` на `TrecTrackEnhanced` во всех файлах!**

Нужно вернуть правильное название `TrecTrackEnhanced` и исправить все ошибки компиляции.

---

## 📋 **План исправлений:**

1. **Исправить TrecTrackEnhanced.kt** - конструктор и типы
2. **Исправить MetadataExtractor.kt** - параметры конструктора  
3. **Добавить импорт Log в DspHandler.kt**
4. **Заменить `TrecTrackEnhanced` на `TrecTrackEnhanced` во всех файлах**
5. **Проверить компиляцию**

---

**Это критические исправления для работы приложения!**
