# What is playing (Spotify, a browser, any app in Windows' media flyout), through the WinRT API
# Windows.Media.Control. Prints one JSON line whenever it changes, until stdin closes:
#   {"title":..,"artist":..,"album":..,"app":..,"playing":true,"art":"<file>"}  or  {} for nothing.
#   nowplaying.ps1 <folder for album art>
param([Parameter(Mandatory)][string]$ArtFolder)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
[void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]

$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait() | Out-Null
    $task.Result
}

$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
New-Item -ItemType Directory -Force -Path $ArtFolder | Out-Null
$last = $null
$lastArtKey = $null
$artFile = $null
$counter = 0

# The app stops this script by closing its input (it never writes to it).
# (Console.In's async reads block on .NET Framework; the raw stream's ReadAsync doesn't.)
$closed = [Console]::OpenStandardInput().ReadAsync((New-Object byte[] 1), 0, 1)

while (-not $closed.IsCompleted) {
    $line = '{}'
    try {
        $session = $manager.GetCurrentSession()
        if ($session) {
            $media = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $status = $session.GetPlaybackInfo().PlaybackStatus.ToString()
            if ($media -and ($media.Title -or $media.Artist)) {
                $artKey = "$($session.SourceAppUserModelId)|$($media.Title)|$($media.Artist)|$($media.AlbumTitle)"
                if ($artKey -ne $lastArtKey) {
                    $lastArtKey = $artKey
                    $artFile = $null
                    if ($media.Thumbnail) {
                        $stream = Await ($media.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                        $source = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                        # A new name each time, so the app never reads a half-written file.
                        $counter++
                        $target = Join-Path $ArtFolder ("art-$PID-$counter.img")
                        $output = [System.IO.File]::Create($target)
                        try { $source.CopyTo($output) } finally { $output.Dispose(); $source.Dispose() }
                        $artFile = $target
                    }
                }
                $line = [ordered]@{
                    title = [string]$media.Title; artist = [string]$media.Artist; album = [string]$media.AlbumTitle
                    app = [string]$session.SourceAppUserModelId; playing = ($status -eq 'Playing'); art = $artFile
                } | ConvertTo-Json -Compress
            }
        }
    } catch {
        $line = '{}'
    }
    if ($line -ne $last) {
        $last = $line
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
    }
    Start-Sleep -Milliseconds 1000
}
