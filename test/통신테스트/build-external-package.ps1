$ErrorActionPreference = 'Stop'

$packageDirectory = $PSScriptRoot
$repositoryRoot = (Resolve-Path (
    Join-Path $packageDirectory '..\..'
)).Path
$environmentFile = Join-Path $repositoryRoot 'web\.env'

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw "Environment file not found: $environmentFile"
}

$passwordLine = Get-Content -LiteralPath $environmentFile |
    Where-Object { $_ -like 'MQTT_ROBOT_PASSWORD=*' } |
    Select-Object -First 1

if ($null -eq $passwordLine) {
    throw 'MQTT_ROBOT_PASSWORD was not found in web\.env.'
}

$password = $passwordLine.Substring(
    'MQTT_ROBOT_PASSWORD='.Length
)
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'MQTT_ROBOT_PASSWORD is empty in web\.env.'
}

$outputDirectory = Join-Path $packageDirectory 'gilbom-mqtt-external-test'
$credentialFile = Join-Path $outputDirectory 'connection-secret.md'
$externalEnvironmentFile = Join-Path $outputDirectory '.env'
$zipFile = Join-Path $packageDirectory 'gilbom-mqtt-external-test-all-topics.zip'
$stagingRoot = Join-Path $packageDirectory '.package-staging'
$stagingDirectory = Join-Path $stagingRoot 'gilbom-mqtt-external-test'
$utf8WithoutBom = New-Object System.Text.UTF8Encoding -ArgumentList $false

New-Item `
    -ItemType Directory `
    -Path $outputDirectory `
    -Force |
    Out-Null

$credentialTemplate = @'
# MQTT test connection secret

This file contains the password for the temporary external MQTT test account.
Share this ZIP only through an authorized private Mattermost channel or DM.

```text
MQTT_HOST=125.137.58.131
MQTT_PORT=8883
MQTT_USERNAME=robot-test
MQTT_PASSWORD={{MQTT_ROBOT_PASSWORD}}
MQTT_CA_CERTIFICATE=local-ca-cert.pem
```

Topics:

```text
tbs/v2/robots/robot-test/state
tbs/v2/robots/robot-test/telemetry
```

This password is only for the temporary robot-test account. Rotate the password
or remove the account after the test. Never commit this file or the ZIP to Git.
'@
$credentialDocument = $credentialTemplate.Replace(
    '{{MQTT_ROBOT_PASSWORD}}',
    $password
)

[System.IO.File]::WriteAllText(
    $credentialFile,
    $credentialDocument,
    $utf8WithoutBom
)

$externalEnvironment = @"
MQTT_HOST=125.137.58.131
MQTT_PORT=8883
MQTT_USERNAME=robot-test
MQTT_PASSWORD=$password
MQTT_CA_CERTIFICATE=local-ca-cert.pem
"@
[System.IO.File]::WriteAllText(
    $externalEnvironmentFile,
    $externalEnvironment,
    $utf8WithoutBom
)

$sourceFiles = @(
    (Join-Path $packageDirectory 'README.md'),
    (Join-Path $packageDirectory 'mqtt_tls_test.py'),
    (Join-Path $packageDirectory 'requirements.txt'),
    (Join-Path $packageDirectory 'local-ca-cert.pem')
)

foreach ($file in $sourceFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required package file not found: $file"
    }
    Copy-Item `
        -LiteralPath $file `
        -Destination $outputDirectory `
        -Force
}

$resolvedPackageDirectory = [System.IO.Path]::GetFullPath($packageDirectory)
$resolvedStagingRoot = [System.IO.Path]::GetFullPath($stagingRoot)
$expectedPrefix = $resolvedPackageDirectory +
    [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedStagingRoot.StartsWith(
    $expectedPrefix,
    [System.StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe staging directory: $resolvedStagingRoot"
}

if (Test-Path -LiteralPath $resolvedStagingRoot) {
    Remove-Item `
        -LiteralPath $resolvedStagingRoot `
        -Recurse `
        -Force
}

try {
    New-Item `
        -ItemType Directory `
        -Path $stagingDirectory `
        -Force |
        Out-Null

    $packageFileNames = @(
        'README.md',
        'mqtt_tls_test.py',
        'requirements.txt',
        'local-ca-cert.pem',
        '.env',
        'connection-secret.md'
    )
    foreach ($fileName in $packageFileNames) {
        Copy-Item `
            -LiteralPath (Join-Path $outputDirectory $fileName) `
            -Destination $stagingDirectory `
            -Force
    }

    Compress-Archive `
        -LiteralPath $stagingDirectory `
        -DestinationPath $zipFile `
        -CompressionLevel Optimal `
        -Force
} finally {
    if (Test-Path -LiteralPath $resolvedStagingRoot) {
        Remove-Item `
            -LiteralPath $resolvedStagingRoot `
            -Recurse `
            -Force
    }
}

$password = $null
$passwordLine = $null

Write-Output "External test ZIP created: $zipFile"
Write-Output "External test folder created: $outputDirectory"
Write-Output '.env and connection-secret.md contain the robot-test password.'
