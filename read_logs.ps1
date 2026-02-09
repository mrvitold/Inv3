# Helper script to read and analyze logcat logs
# Usage: .\read_logs.ps1

$logFile = "app_logcat.txt"
$adbPath = "C:\Users\vitol\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "=== Log Analysis Tool ===" -ForegroundColor Cyan

if (Test-Path $logFile) {
    $content = Get-Content $logFile -ErrorAction SilentlyContinue
    $lineCount = ($content | Measure-Object -Line).Lines
    
    Write-Host "Log file: $logFile" -ForegroundColor Green
    Write-Host "Total lines: $lineCount" -ForegroundColor Green
    
    if ($lineCount -gt 0) {
        Write-Host "`n=== Last 100 lines ===" -ForegroundColor Yellow
        $content | Select-Object -Last 100
        
        Write-Host "`n=== Errors ===" -ForegroundColor Red
        $content | Select-String -Pattern "E/|ERROR|Exception|Error|FAILED" | Select-Object -Last 20
        
        Write-Host "`n=== Warnings ===" -ForegroundColor Yellow
        $content | Select-String -Pattern "W/|WARN|Warning" | Select-Object -Last 20
    } else {
        Write-Host "Log file is empty. Make sure logging is running and reproduce the issue." -ForegroundColor Yellow
    }
} else {
    Write-Host "Log file not found: $logFile" -ForegroundColor Red
    Write-Host "Starting log capture..." -ForegroundColor Yellow
    Start-Process -FilePath $adbPath -ArgumentList "logcat","-c" -NoNewWindow -Wait
    Start-Process -FilePath $adbPath -ArgumentList "logcat","-s","com.vitol.inv3" -RedirectStandardOutput $logFile -NoNewWindow
    Write-Host "Logging started. Reproduce the issue, then run this script again." -ForegroundColor Green
}

