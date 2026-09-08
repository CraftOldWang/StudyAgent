param([string]$Profile = "")

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$keyFile = Join-Path $projectRoot "some_apiKey"
if (-not (Test-Path -LiteralPath $keyFile)) {
    throw "some_apiKey not found. Use named properties: bailian_for_embedding, bailian_openai_compatible, new_deepseek_apiKey."
}

# Spring imports the properties file itself; never copy credentials into command-line arguments.
$keyNames = @(Get-Content -LiteralPath $keyFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*\S+') { $Matches[1] }
})
foreach ($requiredName in @('bailian_for_embedding', 'new_deepseek_apiKey')) {
    if ($requiredName -notin $keyNames) { throw "Missing named property: $requiredName" }
}

Push-Location $projectRoot
try {
    $mavenArgs = @('spring-boot:run')
    if ($Profile) { $mavenArgs += "-Dspring-boot.run.profiles=$Profile" }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "StudyAgent exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
