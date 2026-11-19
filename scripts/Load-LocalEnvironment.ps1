param([Parameter(Mandatory=$true)][ValidateSet('dev','test')][string]$ReadictEnvironment)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskConfig = Get-Content -LiteralPath (Join-Path $taskRoot '.local/database.json') -Raw | ConvertFrom-Json
$env:DB_APP_USER = "readict_${ReadictEnvironment}_app"
$env:DB_MIGRATION_USER = "readict_${ReadictEnvironment}_migrate"
$env:DB_APP_PASSWORD = $taskConfig.($env:DB_APP_USER)
$env:DB_MIGRATION_PASSWORD = $taskConfig.($env:DB_MIGRATION_USER)
$taskTemp = Join-Path $taskRoot '.local/tmp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
# Windows JDK selectors use AF_UNIX internally; an explicit real path avoids TEMP alias failures.
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC -Dfile.encoding=UTF-8 `"-Djdk.net.unixdomain.tmpdir=$taskTemp`""
Write-Host "已加载 $ReadictEnvironment 本机配置（凭据不显示）。"
