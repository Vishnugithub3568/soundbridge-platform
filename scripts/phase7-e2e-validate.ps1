param(
    [Parameter(Mandatory = $true)]
    [string]$SpotifyAccessToken,

    [Parameter(Mandatory = $true)]
    [string]$GoogleAccessToken,

    [string]$BaseUrl = "http://localhost:9000",
    [string]$SpotifySourcePlaylistUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
    [string]$YouTubeSourcePlaylistUrl = "https://music.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth",
    [string]$UserId = "",
    [switch]$RunMigration,
    [int]$PollSeconds = 120,
    [string[]]$ResumeJobIds = @(),
    [switch]$RetryFailedAfterResume
)

$ErrorActionPreference = "Stop"
$failed = $false
$quotaSignals = @()

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
    }

    if (-not [string]::IsNullOrWhiteSpace($UserIdValue)) {
        $startBody.userId = $UserIdValue
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

function Poll-JobUntilTerminal {
    param(
        [string]$JobId,
        [string]$BaseUrlValue,
        [int]$MaxPollSeconds
    )

    $deadline = (Get-Date).AddSeconds($MaxPollSeconds)
    do {
        Start-Sleep -Seconds 2
        $job = Invoke-RestMethod -Method GET -Uri "$BaseUrlValue/migrate/$JobId" -TimeoutSec 30
        $status = [string]$job.status
        Write-Host "Status: $status"

        if ($terminalStatuses -contains $status) {
            $report = Invoke-RestMethod -Method GET -Uri "$BaseUrlValue/migrate/$JobId/report" -TimeoutSec 30
            return @{
                job = $job
                report = $report
            }
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for job $JobId to reach terminal status in $MaxPollSeconds seconds."
}

function Resume-JobAndPoll {
    param(
        [string]$JobId,
        [string]$BaseUrlValue,
        [int]$MaxPollSeconds,
        [bool]$RetryFailed
    )

    if ([string]::IsNullOrWhiteSpace($JobId)) {
        throw "Resume job id is required."
    }

    Write-Host "Resuming job: $JobId"
    $resume = Invoke-RestMethod -Method POST -Uri "$BaseUrlValue/migrate/$JobId/resume" -TimeoutSec 60
    if (-not $resume.id) {
        throw "Resume response missing job id. Response: $($resume | ConvertTo-Json -Compress)"
    }

    $result = Poll-JobUntilTerminal -JobId $JobId -BaseUrlValue $BaseUrlValue -MaxPollSeconds $MaxPollSeconds

    if ($RetryFailed -and (@("FAILED", "PARTIAL_SUCCESS") -contains [string]$result.job.status)) {
        Write-Host "Retrying failed tracks for job: $JobId"
        Invoke-RestMethod -Method POST -Uri "$BaseUrlValue/migrate/$JobId/retry-failed" -TimeoutSec 60 | Out-Null
        $result = Poll-JobUntilTerminal -JobId $JobId -BaseUrlValue $BaseUrlValue -MaxPollSeconds $MaxPollSeconds
    }

    return $result
}

function Show-QuotaGuidance {
    param(
        [string]$Direction,
        [object]$Job,
        [object]$Report,
        [string]$BaseUrlValue
    )

    if ($null -eq $Job -or $null -eq $Report) {
        return
    }

    $status = [string]$Job.status
    $dominantIssue = [string]$Report.dominantIssueCategory
    $isQuota = $status -eq "QUOTA_PAUSED" -or $dominantIssue -eq "QUOTA"

    if (-not $isQuota) {
        return
    }

    $signal = "[$Direction] status=$status issue=$dominantIssue"
    $script:quotaSignals += $signal

    Write-Host ""
    Write-Host "Quota diagnostic: $signal" -ForegroundColor Yellow
    if ($Job.nextRetryTime) {
        Write-Host "Suggested resume time (from job): $($Job.nextRetryTime)" -ForegroundColor Yellow
    }
    Write-Host "Retry guidance:" -ForegroundColor Yellow
    Write-Host "1) Wait for provider quota reset window." -ForegroundColor Yellow
    Write-Host "2) Resume paused jobs with: POST $BaseUrlValue/migrate/<jobId>/resume" -ForegroundColor Yellow
    Write-Host "3) Retry failed tracks with: POST $BaseUrlValue/migrate/<jobId>/retry-failed" -ForegroundColor Yellow
    Write-Host "4) Re-run preflight before large transfers to estimate quota impact." -ForegroundColor Yellow
}

Write-Host "Running Phase 7 authenticated validation against: $BaseUrl"
Write-Host "Run migration mode: $RunMigration"
if ($ResumeJobIds.Count -gt 0) {
    Write-Host "Resume mode job ids: $($ResumeJobIds -join ', ')"
}

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
        Show-QuotaGuidance -Direction "SPOTIFY_TO_YOUTUBE" -Job $result.job -Report $result.report -BaseUrlValue $BaseUrl
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
        Show-QuotaGuidance -Direction "YOUTUBE_TO_SPOTIFY" -Job $result.job -Report $result.report -BaseUrlValue $BaseUrl
    }
}

if ($ResumeJobIds.Count -gt 0) {
    foreach ($jobId in $ResumeJobIds) {
        Invoke-Step -Name "POST /migrate/$jobId/resume + poll" -Action {
            $result = Resume-JobAndPoll -JobId $jobId -BaseUrlValue $BaseUrl -MaxPollSeconds $PollSeconds -RetryFailed:$RetryFailedAfterResume
            Write-Host "Final status: $($result.job.status)"
            Write-Host ($result.report | ConvertTo-Json -Depth 10)
            Show-QuotaGuidance -Direction "RESUME" -Job $result.job -Report $result.report -BaseUrlValue $BaseUrl
        }
    }
}

Write-Host ""
if ($quotaSignals.Count -gt 0) {
    Write-Host "Quota summary:" -ForegroundColor Yellow
    $quotaSignals | ForEach-Object { Write-Host "- $_" -ForegroundColor Yellow }
    Write-Host ""
}

if ($failed) {
    Write-Host "Phase 7 validation completed with failures." -ForegroundColor Red
    exit 1
}

Write-Host "Phase 7 validation passed." -ForegroundColor Green
exit 0
