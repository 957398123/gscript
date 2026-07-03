# Build gscript extension VSIX package and install via code --install-extension
# VSIX is an OPC (Open Packaging Conventions) ZIP package

$ErrorActionPreference = "Stop"

$tmp = Join-Path $env:TEMP "gscript-vsix-build"
Write-Host "=== Build dir: $tmp ==="
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tmp "extension") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tmp "extension\src") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tmp "extension\syntaxes") -Force | Out-Null

# Copy extension files into extension/ subdir
$src = "e:\JProjects\gscript\vscode-extension"
Copy-Item (Join-Path $src "package.json") (Join-Path $tmp "extension\package.json") -Force
Copy-Item (Join-Path $src "gscript-language-configuration.json") (Join-Path $tmp "extension\gscript-language-configuration.json") -Force
Copy-Item (Join-Path $src "src\extension.js") (Join-Path $tmp "extension\src\extension.js") -Force
Copy-Item (Join-Path $src "syntaxes\gscript.tmLanguage.json") (Join-Path $tmp "extension\syntaxes\gscript.tmLanguage.json") -Force
Copy-Item (Join-Path $src "README.md") (Join-Path $tmp "extension\README.md") -Force
Write-Host "Extension files copied"

# Write extension.vsixmanifest (OPC manifest)
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="gscript-debug" Version="0.2.0" Publisher="gscript" />
    <DisplayName>gscript Debug</DisplayName>
    <Description xml:space="preserve">gscript debugger and syntax highlight</Description>
    <Tags>debuggers,programming languages</Tags>
  </Metadata>
  <Installation InstalledByMsi="false">
    <InstallationTarget Id="Microsoft.VisualStudio.Code" />
  </Installation>
  <Dependencies>
    <Dependency Id="Microsoft.VisualStudio.Code" Version="1.60.0" />
  </Dependencies>
  <Assets>
    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />
  </Assets>
</PackageManifest>
'@
$manifestPath = Join-Path $tmp "extension.vsixmanifest"
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding UTF8 -NoNewline
Write-Host "extension.vsixmanifest written"

# Write [Content_Types].xml (OPC content types)
$ct = @'
<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="vsixmanifest" ContentType="text/xml" />
  <Default Extension="json" ContentType="application/json" />
  <Default Extension="js" ContentType="text/javascript" />
  <Default Extension="md" ContentType="text/markdown" />
  <Default Extension="xml" ContentType="text/xml" />
</Types>
'@
$ctPath = Join-Path $tmp "[Content_Types].xml"
Set-Content -LiteralPath $ctPath -Value $ct -Encoding UTF8 -NoNewline
Write-Host "[Content_Types].xml written"

# Package as ZIP (VSIX is essentially a ZIP)
$zipPath = Join-Path $env:TEMP "gscript-debug-0.2.0.zip"
$vsixPath = Join-Path $env:TEMP "gscript-debug-0.2.0.vsix"
Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
Remove-Item $vsixPath -Force -ErrorAction SilentlyContinue

# Use .NET ZipFile API to package
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($tmp, $zipPath)
Write-Host "ZIP created: $zipPath"

# Rename to .vsix
Copy-Item $zipPath $vsixPath -Force
Write-Host "VSIX created: $vsixPath"

# Verify VSIX contents
Write-Host ""
Write-Host "=== VSIX contents ==="
$zip = [System.IO.Compression.ZipFile]::OpenRead($vsixPath)
foreach ($entry in $zip.Entries) {
    Write-Host ("  " + $entry.FullName + "  (" + $entry.Length + " bytes)")
}
$zip.Dispose()

Write-Host ""
Write-Host "=== VSIX build complete ==="
Write-Host "VSIX path: $vsixPath"
