# Lock screen image through the official WinRT API (Windows.System.UserProfile.LockScreen).
#   lockscreen.ps1 set  <image file>   - show this picture on the lock screen
#   lockscreen.ps1 read <output file>  - save the picture currently shown
param([Parameter(Mandatory)][string]$Action, [Parameter(Mandatory)][string]$Path)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
[void][Windows.System.UserProfile.LockScreen, Windows.System.UserProfile, ContentType = WindowsRuntime]

$extensions = [System.WindowsRuntimeSystemExtensions].GetMethods()
$asTaskGeneric = $extensions | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
$asTaskAction = $extensions | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
} | Select-Object -First 1

function Await($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait() | Out-Null
    $task.Result
}

function AwaitAction($action) {
    $task = $asTaskAction.Invoke($null, @($action))
    $task.Wait() | Out-Null
}

switch ($Action) {
    'set' {
        $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
        AwaitAction ([Windows.System.UserProfile.LockScreen]::SetImageFileAsync($file))
    }
    'read' {
        $stream = [Windows.System.UserProfile.LockScreen]::GetImageStream()
        $source = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $output = [System.IO.File]::Create($Path)
        try { $source.CopyTo($output) } finally { $output.Dispose(); $source.Dispose() }
    }
    default { throw "Unknown action: $Action" }
}
