# Build script for SmarterKeepInventory plugin
[xml]$pom = Get-Content -Path "pom.xml" -Raw
$release = $pom.project.properties.'maven.compiler.release'

$jdkPaths = @{
    "25" = "C:\Users\speed\OneDrive\Programming\mc-plugins\jdk25"
    "21" = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
}

if ($jdkPaths.ContainsKey($release)) {
    $env:JAVA_HOME = $jdkPaths[$release]
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
}

$mvnArgs = @("clean", "package")

# Suppress download progress bars in CI (GitHub Actions)
if ($env:CI) {
    $mvnArgs += "--no-transfer-progress"
}

# Force delete target folder locally to avoid locked file issues
if (-not $env:CI) {
    Remove-Item -Path "target" -Recurse -Force -ErrorAction SilentlyContinue
}

mvn $mvnArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "`nBuild failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

# ---- Post-build cleanup ----
# Maven shade (configured to replace the original artifact by default) leaves behind
# the unshaded "original-*.jar". We just delete that, then ensure only the canonical
# artifactId-version.jar (the shaded final) remains. The script is a no-op if shade
# is not configured.
[xml]$pom = Get-Content -Path "pom.xml" -Raw
$version = $pom.project.version
$artifactId = $pom.project.artifactId
$finalName = "$artifactId-$version.jar"

# Remove the unshaded leftover that maven-shade-plugin creates
Get-ChildItem -Path "target" -Filter "original-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item -Path $_.FullName -Force
}

# Remove any leftover *-shaded.jar (in case shade was configured to keep both)
Get-ChildItem -Path "target" -Filter "*-shaded.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item -Path $_.FullName -Force
}

# If only the *-shaded.jar exists (shade configured NOT to replace), rename it
if (-not (Test-Path "target/$finalName") -and (Test-Path "target/$artifactId-$version-shaded.jar")) {
    Rename-Item -Path "target/$artifactId-$version-shaded.jar" -NewName $finalName
}

if (Test-Path "target/$finalName") {
    Write-Host "`nFinal JAR: target/$finalName" -ForegroundColor Cyan
    Write-Host "Build successful!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nBuild succeeded but final JAR was not found!" -ForegroundColor Red
    exit 1
}
