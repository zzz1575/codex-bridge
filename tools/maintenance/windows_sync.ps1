param([Parameter(Mandatory = $true)][string]$Config)
$ErrorActionPreference = 'Stop'
$settings = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
$stateDir = [IO.Path]::GetFullPath($settings.state_dir)
New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
$lock = $null
for ($attempt = 0; $attempt -lt 240 -and !$lock; $attempt++) {
    try { $lock = [IO.File]::Open((Join-Path $stateDir 'upload.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { Start-Sleep -Milliseconds 250 }
}
if (!$lock) { throw 'A source upload is still active; retry the upload command' }
try {
    $project = [IO.Path]::GetFullPath($settings.source_repo)
    $privateValues = @()
    $accessPath = Join-Path $project '.private/access.json'
    if (Test-Path -LiteralPath $accessPath) {
        $access = Get-Content -LiteralPath $accessPath -Raw | ConvertFrom-Json
        $privateValues = @($access.url, $access.password) | Where-Object { $_ -and $_.Length -ge 8 }
    }
    $sourceRef = if ($settings.source_ref) { $settings.source_ref } else { 'refs/heads/main' }
    $target = (& $settings.git -C $project rev-parse "$($sourceRef)^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }
    $files = @(& $settings.git -C $project ls-tree -r --name-only $target)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }
    foreach ($name in $files) {
        if ($name -match '(^|/)(\.private|\.gradle|build|outputs|local\.properties|node_modules)(/|$)|\.(apk|aab|jks|keystore|log|bundle)$') {
            throw "Private or generated file is committed: $name"
        }
        $body = (& $settings.git -C $project show "${target}:$name") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect source file: $name" }
        if ($privateValues | Where-Object { $body.Contains($_) }) { throw "Private configuration value detected: $name" }
    }
    $id = [guid]::NewGuid().ToString('N')
    $filename = "android-$id.bundle"
    $bundle = Join-Path $stateDir $filename
    & $settings.git -C $project update-ref refs/source-sync/export $target
    if ($LASTEXITCODE -ne 0) { throw 'Cannot pin source upload snapshot' }
    & $settings.git -C $project bundle create $bundle refs/source-sync/export
    if ($LASTEXITCODE -ne 0) { throw 'Source bundle creation failed' }
    & scp -q -o BatchMode=yes -o ConnectTimeout=10 $bundle "$($settings.ssh_host):$($settings.remote_incoming)/$filename"
    if ($LASTEXITCODE -ne 0) { throw 'SSH source upload failed' }
    # Config paths are administrator-owned. The generated basename contains only hex digits.
    & ssh -o BatchMode=yes -o ConnectTimeout=10 $settings.ssh_host python3 $settings.remote_script --config $settings.remote_config receive-android $filename
    if ($LASTEXITCODE -ne 0) { throw 'Server rejected source upload' }
    Remove-Item -LiteralPath $bundle
    @{ status = 'queued'; updated_at = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateDir 'status.json') -Encoding utf8
} catch {
    @{ status = 'blocked'; error = $_.Exception.Message; updated_at = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateDir 'status.json') -Encoding utf8
    throw
} finally { if ($lock) { $lock.Dispose() } }
