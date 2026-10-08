# Packs a Subnauticraft release into dist\ from the two built halves:
#   Subnauticraft-Subnautica-<version>.zip   the Subnautica plugin, laid out from the game's folder
#                                            (BepInEx\plugins\MinecraftLink\MinecraftLink.dll)
#   Subnauticraft-Minecraft-<version>.zip    a portable Prism Launcher with a ready "Subnauticraft"
#                                            instance (Minecraft 1.21.1, Fabric Loader, Fabric API,
#                                            the Subnauticraft mod), unpacked to
#                                            %LOCALAPPDATA%\Subnauticraft
# Prism asks the player to sign in once, then downloads Minecraft, Fabric Loader and Java itself.
# Build both halves first (minecraft\build.bat and subnautica\build.bat).
#
#   powershell -ExecutionPolicy Bypass -File tools\package.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path "$root\minecraft\gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()

# Pinned downloads (checked against these hashes).
$prismVersion = "11.1.1"
$prismZip = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"
$prismUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$prismZip"
$prismSha256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
$prismLicenseUrl = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/$prismVersion/LICENSE"
$fabricApiJar = "fabric-api-0.116.17+1.21.1.jar"
$fabricApiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/Mys3P7lK/fabric-api-0.116.17%2B1.21.1.jar"
$fabricApiSha512 = "98c478217da19181f0e4df3ea6c2da6bdbda271c7544a4aea048513a49b444f1ff5b71dc0df6303017c48e8ff69b7bb6af6179bdf0d0e09ab3c1ee08411857a8"

function Get-Pinned([string]$url, [string]$path, [string]$algorithm, [string]$hash) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory (Split-Path $path) -Force | Out-Null
        Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
    }
    if ($hash -and (Get-FileHash $path -Algorithm $algorithm).Hash -ne $hash.ToUpper()) {
        Remove-Item $path
        throw "$path doesn't match its pinned $algorithm hash"
    }
}

# Zip entries named with forward slashes, as the zip format expects; Windows PowerShell's own
# zipping writes backslashes.
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
function New-Zip([string]$path, [System.Collections.IDictionary]$entries) {
    $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $entries.Keys) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $entries[$name], $name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $zip.Dispose() }
}
function New-ZipFromFolder([string]$path, [string]$folder) {
    $entries = [ordered]@{}
    $base = (Resolve-Path $folder).Path.TrimEnd('\') + '\'
    Get-ChildItem $folder -Recurse -File | Sort-Object FullName | ForEach-Object {
        $entries[$_.FullName.Substring($base.Length).Replace('\', '/')] = $_.FullName
    }
    New-Zip $path $entries
}

$jar = "$root\minecraft\build\libs\subnautica_link-$version.jar"
$dll = "$root\subnautica\bin\Release\net472\MinecraftLink.dll"
foreach ($f in @($jar, $dll)) {
    if (-not (Test-Path $f)) { throw "missing $f (build both halves first)" }
}
$dllVersion = (Get-Item $dll).VersionInfo.ProductVersion
if (-not $dllVersion.StartsWith($version)) { throw "MinecraftLink.dll is $dllVersion but the jar is $version; rebuild both" }

$cache = "$root\.tools\prism"
Get-Pinned $prismUrl "$cache\$prismZip" SHA256 $prismSha256
Get-Pinned $fabricApiUrl "$cache\$fabricApiJar" SHA512 $fabricApiSha512
Get-Pinned $prismLicenseUrl "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "" ""

$dist = "$root\dist"
New-Item -ItemType Directory $dist -Force | Out-Null
Get-ChildItem $dist | Remove-Item -Recurse -Force

# The bundled Minecraft: Prism (portable), the Subnauticraft instance and its mods.
$bundle = "$dist\bundle"
Copy-Item -Recurse "$root\tools\minecraft-bundle" $bundle
Expand-Archive "$cache\$prismZip" "$bundle\Prism" -Force
Copy-Item "$cache\PrismLauncher-$prismVersion-LICENSE.txt" "$bundle\Prism\LICENSE-PrismLauncher.txt"
(Get-Content "$bundle\Prism\THIRD-PARTY.txt" -Raw).Replace("{PRISM_VERSION}", $prismVersion) | Set-Content "$bundle\Prism\THIRD-PARTY.txt" -NoNewline
$mods = "$bundle\Prism\instances\Subnauticraft\.minecraft\mods"
New-Item -ItemType Directory $mods -Force | Out-Null
Copy-Item "$cache\$fabricApiJar" $mods
Copy-Item $jar $mods
Copy-Item "$root\LICENSE" "$bundle\LICENSE-Subnauticraft.txt"
Set-Content "$bundle\bundle-version.txt" "Subnauticraft $version, Prism Launcher $prismVersion, $fabricApiJar" -NoNewline
New-ZipFromFolder "$dist\Subnauticraft-Minecraft-$version.zip" $bundle
Remove-Item -Recurse -Force $bundle

New-Zip "$dist\Subnauticraft-Subnautica-$version.zip" ([ordered]@{
    "BepInEx/plugins/MinecraftLink/MinecraftLink.dll" = $dll
    "BepInEx/plugins/MinecraftLink/LICENSE.txt" = "$root\LICENSE"
})

Get-ChildItem $dist | ForEach-Object { "{0,-44} {1,12:N0} bytes  {2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() }
