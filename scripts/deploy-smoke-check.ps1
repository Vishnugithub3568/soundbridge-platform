param(
    [string]$BaseUrl = "http://localhost:9000",
    [string]$UserId = "11111111-1111-1111-1111-111111111111",
    [string]$SourcePlaylistUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
)

$ErrorActionPreference = "Stop"
$failed = $false

function Invoke-SmokeStep {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "== $Name =="
    try {
        & $Action
        Write-Host "PASS: $Name" -ForegroundColor Green
    } catch {
        $script:failed = $true
        Write-Host "FAIL: $Name" -ForegroundColor Red
        Write-Host $_.Exception.Message
    }
}

Invoke-SmokeStep -Name "GET /health" -Action {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/health" -TimeoutSec 20
    if ($resp.status -ne "ok") {
        throw "Expected status=ok, got: $($resp | ConvertTo-Json -Compress)"
    }
    $resp | ConvertTo-Json -Depth 5
}

Invoke-SmokeStep -Name "GET /diagnose" -Action {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/diagnose" -TimeoutSec 20
    if ($resp.status -ne "ok") {
        throw "Expected diagnose status=ok, got: $($resp | ConvertTo-Json -Compress)"
    }
    $resp | ConvertTo-Json -Depth 5
}

Invoke-SmokeStep -Name "GET /migrate/history" -Action {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/migrate/history?userId=$UserId&limit=5" -TimeoutSec 30
    if ($null -eq $resp) {
        throw "Expected JSON response for history"
    }
    $resp | ConvertTo-Json -Depth 6
}

Invoke-SmokeStep -Name "POST /migrate/preflight" -Action {
    $body = @{
        sourcePlaylistUrl = $SourcePlaylistUrl
        direction = "SPOTIFY_TO_YOUTUBE"
    } | ConvertTo-Json

    $resp = Invoke-RestMethod -Method POST -Uri "$BaseUrl/migrate/preflight" -ContentType "application/json" -Body $body -TimeoutSec 90
    if (-not $resp.validUrl) {
        throw "Expected validUrl=true, got: $($resp | ConvertTo-Json -Compress)"
    }
    $resp | ConvertTo-Json -Depth 8
}

Write-Host ""
if ($failed) {
    Write-Host "Smoke check completed with failures." -ForegroundColor Red
    exit 1
}

Write-Host "Smoke check passed." -ForegroundColor Green
exit 0
