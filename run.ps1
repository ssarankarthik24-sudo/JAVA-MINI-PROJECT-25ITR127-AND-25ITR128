# Compile and run the ATC Safety System
# Prerequisites: JDK 17+, mysql-connector-j-8.x.jar in lib/

$lib = "lib\mysql-connector-j-8.0.33.jar"
$src = "src"
$out = "out"
$classpath = [string]::Join(';', @($out, $lib))

# Create output directory
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Compile
Write-Host "==> Compiling ATC Safety System..." -ForegroundColor Cyan

if (Test-Path $lib) {
    javac -cp "$lib" -d $out (Get-ChildItem $src\*.java | ForEach-Object { $_.FullName })
} else {
    Write-Host "[WARN] MySQL connector not found at $lib; compiling without DB support" -ForegroundColor Yellow
    javac -d $out (Get-ChildItem $src\*.java | ForEach-Object { $_.FullName })
}

if ($LASTEXITCODE -eq 0) {
    Write-Host "==> Compilation successful. Running MainApp..." -ForegroundColor Green
    if (Test-Path $lib) {
        java -cp $classpath MainApp
    } else {
        java -cp $out MainApp
    }
} else {
    Write-Host "==> Compilation FAILED. Check errors above." -ForegroundColor Red
}
