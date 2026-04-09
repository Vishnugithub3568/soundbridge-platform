param(
    [Parameter(Mandatory = $true)]
    [string]$SpotifyAccessToken,

    [Parameter(Mandatory = $true)]
    [string]$GoogleAccessToken,

    [string]$BaseUrl = "http://localhost:9000",
    [string]$SpotifySourcePlaylistUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
    [string]$YouTubeSourcePlaylistUrl = "https://music.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth",
    [string]$UserId = "11111111-1111-1111-1111-111111111111",
    [switch]$RunMigration,
    [int]$PollSeconds = 120
)

$ErrorActionPreference = "Stop"
$failed = $false

$terminalStatuses = @("COMPLETED", "FAILED", "PARTIAL_SUCCESS", "QUOTA_PAUSED")

function Invoke-Step {
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

function Invoke-JsonPost {
    param(
        [string]$Url,
        [hashtable]$Body,
        [int]$TimeoutSec = 90
    )

    $json = $Body | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Method POST -Uri $Url -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSec
}

function Start-MigrationAndPoll {
    param(
        [string]$Direction,
        [string]$SourcePlaylistUrl,
        [string]$SpotifyAccessTokenValue,
        [string]$GoogleAccessTokenValue,
        [string]$UserIdValue,
        [string]$BaseUrlValue,
        [int]$MaxPollSeconds
    )

    $startBody = @{
        sourcePlaylistUrl = $SourcePlaylistUrl
        direction = $Direction
        spotifyAccessToken = $SpotifyAccessTokenValue
        googleAccessToken = $GoogleAccessTokenValue
        userId = $UserIdValue
    }

    $started = Invoke-JsonPost -Url "$BaseUrlValue/migrate" -Body $startBody -TimeoutSec 90
    if (-not $started.id) {
        throw "Migration start response missing job id. Response: $($started | ConvertTo-Json -Compress)"
    }

    $jobId = [string]$started.id
    Write-Host "Started job: $jobId"

    $deadline = (Get-Date).AddSeconds($MaxPollSeconds)
    do {
        Start-Sleep -Seconds 2
        $job = Invoke-RestMethod -Method GET -Uri "$BaseUrlValue/migrate/$jobId" -TimeoutSec 30
        $status = [string]$job.status
        Write-Host "Status: $status"

        if ($terminalStatuses -contains $status) {
            $report = Invoke-RestMethod -Method GET -Uri "$BaseUrlValue/migrate/$jobId/report" -TimeoutSec 30
            return @{
                job = $job
                report = $report
            }
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for job $jobId to reach terminal status in $MaxPollSeconds seconds."
}

Write-Host "Running Phase 7 authenticated validation against: $BaseUrl"
Write-Host "Run migration mode: $RunMigration"

Invoke-Step -Name "GET /health" -Action {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/health" -TimeoutSec 20
    if ($resp.status -ne "ok") {
        throw "Expected status=ok, got: $($resp | ConvertTo-Json -Compress)"
    }
}

Invoke-Step -Name "POST /migrate/preflight (SPOTIFY_TO_YOUTUBE)" -Action {
    $resp = Invoke-JsonPost -Url "$BaseUrl/migrate/preflight" -Body @{
        sourcePlaylistUrl = $SpotifySourcePlaylistUrl
        direction = "SPOTIFY_TO_YOUTUBE"
        spotifyAccessToken = $SpotifyAccessToken
        googleAccessToken = $GoogleAccessToken
    }

    if (-not $resp.validUrl) {
        throw "Expected validUrl=true for Spotify source URL. Response: $($resp | ConvertTo-Json -Compress)"
    }

    Write-Host ($resp | ConvertTo-Json -Depth 8)
}

Invoke-Step -Name "POST /migrate/preflight (YOUTUBE_TO_SPOTIFY)" -Action {
    $resp = Invoke-JsonPost -Url "$BaseUrl/migrate/preflight" -Body @{
        sourcePlaylistUrl = $YouTubeSourcePlaylistUrl
        direction = "YOUTUBE_TO_SPOTIFY"
        spotifyAccessToken = $SpotifyAccessToken
        googleAccessToken = $GoogleAccessToken
    }

    if (-not $resp.validUrl) {
        throw "Expected validUrl=true for YouTube source URL. Response: $($resp | ConvertTo-Json -Compress)"
    }

    Write-Host ($resp | ConvertTo-Json -Depth 8)
}

if ($RunMigration) {
    Invoke-Step -Name "POST /migrate + poll (SPOTIFY_TO_YOUTUBE)" -Action {
        $result = Start-MigrationAndPoll `
            -Direction "SPOTIFY_TO_YOUTUBE" `
            -SourcePlaylistUrl $SpotifySourcePlaylistUrl `
            -SpotifyAccessTokenValue $SpotifyAccessToken `
            -GoogleAccessTokenValue $GoogleAccessToken `
            -UserIdValue $UserId `
            -BaseUrlValue $BaseUrl `
            -MaxPollSeconds $PollSeconds

        Write-Host "Final status: $($result.job.status)"
        Write-Host ($result.report | ConvertTo-Json -Depth 10)
    }

    Invoke-Step -Name "POST /migrate + poll (YOUTUBE_TO_SPOTIFY)" -Action {
        $result = Start-MigrationAndPoll `
            -Direction "YOUTUBE_TO_SPOTIFY" `
            -SourcePlaylistUrl $YouTubeSourcePlaylistUrl `
            -SpotifyAccessTokenValue $SpotifyAccessToken `
            -GoogleAccessTokenValue $GoogleAccessToken `
            -UserIdValue $UserId `
            -BaseUrlValue $BaseUrl `
            -MaxPollSeconds $PollSeconds

        Write-Host "Final status: $($result.job.status)"
        Write-Host ($result.report | ConvertTo-Json -Depth 10)
    }
}

Write-Host ""
if ($failed) {
    Write-Host "Phase 7 validation completed with failures." -ForegroundColor Red
    exit 1
}

Write-Host "Phase 7 validation passed." -ForegroundColor Green
exit 0
