param([string]$TestRoot = (Join-Path ([IO.Path]::GetTempPath()) ('codex-source-ref-' + [guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $TestRoot) { throw 'Fixture directory must be new' }
New-Item -ItemType Directory -Path "$TestRoot/source","$TestRoot/state" -Force | Out-Null
$git = (Get-Command git).Source
$repo = Join-Path $TestRoot 'source'
& $git -C $repo init -q -b main
& $git -C $repo config user.name Fixture
& $git -C $repo config user.email fixture@example.com
Set-Content -LiteralPath "$repo/README.md" -Value 'safe main source'
& $git -C $repo add README.md
& $git -C $repo commit -qm baseline
$main = (& $git -C $repo rev-parse main).Trim()
& $git -C $repo switch -qc feature/draft
New-Item -ItemType Directory -Path "$repo/.private" | Out-Null
Set-Content -LiteralPath "$repo/.private/forbidden.txt" -Value 'fixture-only unfinished work'
& $git -C $repo add .private/forbidden.txt
& $git -C $repo commit -qm draft
$draft = (& $git -C $repo rev-parse HEAD).Trim()
$config = Join-Path $TestRoot 'config.json'
@{git=$git; source_repo=$repo; source_ref='refs/heads/main'; state_dir="$TestRoot/state"; ssh_host='fixture'; remote_incoming='/fixture'; remote_script='/fixture/script'; remote_config='/fixture/config'} | ConvertTo-Json | Set-Content -LiteralPath $config
$global:MaintenanceFixtureReceived = Join-Path $TestRoot 'received.bundle'
$global:MaintenanceFixtureRoot = $TestRoot
function scp {
    $fixtureBundle = @($args | Where-Object { $_ -like '*.bundle' -and $_.StartsWith($global:MaintenanceFixtureRoot) })[0]
    if (!$fixtureBundle) { throw 'Mock transfer did not receive the pinned source bundle' }
    Copy-Item -LiteralPath $fixtureBundle -Destination $global:MaintenanceFixtureReceived
    $global:LASTEXITCODE = 0
}
function ssh { $global:LASTEXITCODE = 0 }
$helper = Join-Path (Split-Path $PSScriptRoot -Parent) 'windows_sync.ps1'
& $helper -Config $config
if (!(Test-Path -LiteralPath $global:MaintenanceFixtureReceived)) { throw 'No fixed-branch snapshot was transferred' }
$heads = (& $git -C $repo bundle list-heads $global:MaintenanceFixtureReceived) -join "`n"
if (!$heads.StartsWith($main + ' ')) { throw 'Draft branch was uploaded instead of configured source branch' }
if ((& $git -C $repo rev-parse HEAD).Trim() -ne $draft) { throw 'Synchronization modified the development checkout' }
Write-Output 'PASS: fixed source branch, ignored draft private file, pinned snapshot, unchanged checkout; network mocked'
