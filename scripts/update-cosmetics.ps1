param(
    [string]$PayloadFile = ".local-secrets/jsonhosting-payload.json",
    [string]$WorkerUrl = "https://plain-dawn-a5d2.ryaneagers2015.workers.dev/cosmetics/people"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $PayloadFile)) {
    throw "Payload file not found: $PayloadFile"
}

if (-not $env:COSMETICS_WRITE_TOKEN) {
    throw "COSMETICS_WRITE_TOKEN is not set in the environment."
}

$payloadText = Get-Content -LiteralPath $PayloadFile -Raw

try {
    $null = $payloadText | ConvertFrom-Json
}
catch {
    throw "Payload file is not valid JSON: $PayloadFile"
}

$headers = @{
    Authorization = "Bearer $($env:COSMETICS_WRITE_TOKEN)"
}

$response = Invoke-RestMethod `
    -Method Patch `
    -Uri $WorkerUrl `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $payloadText

$response | ConvertTo-Json -Depth 10
