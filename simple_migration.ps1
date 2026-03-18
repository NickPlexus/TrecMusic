# simple_migration.ps1
# Simple migration script without special characters

$projectPath = "C:\Users\NPC\AndroidStudioProjects\TrecMusic\app\src\main\java\com\trec\music"

Write-Host "Starting migration from TrecTrackEnhanced to TrecTrackEnhanced..." -ForegroundColor Green

$files = @(
    "$projectPath\viewmodel\MusicViewModel.kt",
    "$projectPath\data\LibraryHandler.kt",
    "$projectPath\data\LibraryRepository.kt",
    "$projectPath\ui\components\TrackComponents.kt",
    "$projectPath\ui\screens\FullPlayerOverlay.kt",
    "$projectPath\ui\screens\FavoritesScreen.kt",
    "$projectPath\ui\screens\HomeScreen.kt",
    "$projectPath\ui\components\MiniPlayer.kt",
    "$projectPath\PrefsManager.kt",
    "$projectPath\utils\AudioReverser.kt",
    "$projectPath\utils\VocalRemoverEngine.kt"
)

$processedCount = 0
$errorCount = 0

foreach ($file in $files) {
    if (Test-Path $file) {
        try {
            Write-Host "Processing: $file" -ForegroundColor Yellow
            
            $content = Get-Content $file -Raw -Encoding UTF8
            
            if ($content -match "TrecTrackEnhanced") {
                # Replace imports
                $content = $content -replace "import com.trec.music.data.TrecTrackEnhanced", "import com.trec.music.data.TrecTrackEnhanced"
                
                # Replace all TrecTrackEnhanced occurrences
                $content = $content -replace "\bTrecTrackEnhanced\b", "TrecTrackEnhanced"
                
                Set-Content -Path $file -Value $content -Encoding UTF8
                Write-Host "Updated: $file" -ForegroundColor Green
                $processedCount++
            } else {
                Write-Host "No TrecTrackEnhanced references found in: $file" -ForegroundColor Cyan
            }
        }
        catch {
            Write-Host "Error processing $file: $($_)" -ForegroundColor Red
            $errorCount++
        }
    } else {
        Write-Host "File not found: $file" -ForegroundColor Red
        $errorCount++
    }
}

# Delete old file
$oldTrackFile = "$projectPath\data\TrecTrackEnhanced.kt"
if (Test-Path $oldTrackFile) {
    try {
        Remove-Item $oldTrackFile -Force
        Write-Host "Deleted old TrecTrackEnhanced.kt" -ForegroundColor Green
    }
    catch {
        Write-Host "Error deleting old TrecTrackEnhanced.kt: $($_)" -ForegroundColor Red
        $errorCount++
    }
}

Write-Host ""
Write-Host "Migration Summary:" -ForegroundColor Magenta
Write-Host "Files processed: $processedCount" -ForegroundColor Green
Write-Host "Errors: $errorCount" -ForegroundColor Red
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "1. Run: ./gradlew compileDebugKotlin"
Write-Host "2. Check for any remaining TrecTrackEnhanced references"
Write-Host "3. Test the app with enhanced metadata"
Write-Host ""
Write-Host "Migration completed!" -ForegroundColor Green
