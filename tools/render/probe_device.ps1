# Runs the app's own WebPreview.verify against a local project directory, via the debug bridge.
# No model calls, no agent run: this is the "what would the agent see" check.
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Shot = "",
    [string]$Steps = "[]",
    [string]$Base = "http://127.0.0.1:18765"
)
$ErrorActionPreference = "Stop"
$files = [ordered]@{}
$manifestPath = Join-Path $Dir "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "缺少 manifest.json：$manifestPath" }
foreach ($item in Get-ChildItem -LiteralPath $Dir -Recurse -File) {
    $relative = $item.FullName.Substring((Resolve-Path -LiteralPath $Dir).Path.Length + 1).Replace("\", "/")
    if ($relative -eq "manifest.json") { continue }
    $files[$relative] = (Get-Content -LiteralPath $item.FullName -Raw -Encoding UTF8)
}
$body = [ordered]@{
    action   = "preview"
    manifest = (Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    files    = $files
    # @() 不能省：PowerShell 会把单元素数组解包成对象，JSON 里就不再是数组，
    # 宿主侧 optJSONArray("steps") 会拿到 null，于是"一条步骤都没跑"却看起来正常。
    steps    = @($Steps | ConvertFrom-Json)
}
if ($Shot) { $body["shot"] = $Shot }
$json = $body | ConvertTo-Json -Depth 12 -Compress
$response = Invoke-WebRequest -Uri "$Base/test/web" -Method POST -Body $json `
    -ContentType "application/json" -Headers @{ "x-goon-test-token" = $Token } -TimeoutSec 60
$response.Content
