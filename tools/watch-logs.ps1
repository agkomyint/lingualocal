# Live view of what LinguaLocal sends to / gets from Gemini, on your computer.
# Usage (tablet connected by USB or wireless adb):  powershell -ExecutionPolicy Bypass -File tools\watch-logs.ps1
#
#   SegmentTranslator  each piece: Gemini answer time, transcript ("heard") and translation ("said")
#   LiveInterpreter  Gemini Live connection (live preview text)
#   ListenService    mic level / voice detector, segments skipped on device as noise
#   PushToTalk       on-device English recognition and English -> Thai results

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

& $adb logcat -c
Write-Host "Watching LinguaLocal logs (Ctrl+C to stop)..." -ForegroundColor Cyan
& $adb logcat -v time "LiveInterpreter:V" "SegmentTranslator:V" "OfflineSpeech:V" "LanguageId:V" "SpeakerId:V" "OfflineProbe:V" "GemmaAssistant:V" "HybridAssistant:V" "ListenService:V" "PushToTalk:V" "AndroidRuntime:E" "*:S" | ForEach-Object {
    if ($_ -match "turn done|->") { Write-Host $_ -ForegroundColor Green }
    elseif ($_ -match "skipped on device") { Write-Host $_ -ForegroundColor DarkGray }
    elseif ($_ -match " [EW]/") { Write-Host $_ -ForegroundColor Yellow }
    else { Write-Host $_ }
}
