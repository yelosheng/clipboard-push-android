# Clipboard Man - vcpkg Fix Script V4 (Final Hash)
$vcpkgRoot = "D:/vcpkg"
$portsDir = "$vcpkgRoot/ports"

function Update-Port {
    param($name, $json, $cmake)
    $path = "$portsDir/$name"
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    Set-Content -Path "$path/vcpkg.json" -Value $json -Encoding UTF8
    Set-Content -Path "$path/portfile.cmake" -Value $cmake -Encoding UTF8
}

# --- 1. socket-io-client-cpp (使用你反馈的 Actual Hash) ---
$sioJson = @'
{
  "name": "socket-io-client-cpp",
  "version": "3.1.0",
  "dependencies": ["websocketpp", "rapidjson"]
}
'@
$sioCmake = @'
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO socketio/socket.io-client-cpp
    REF 3.1.0
    SHA512 a0adaa06ddb24297686a40b1e71a55bbab437093f828f76040c376b4adccb7d8b06eff4d8569dbde9b2e071257b3290e7e2bffd6354b33ecf67378ffa1d0cc13
)
vcpkg_cmake_configure(SOURCE_PATH "${SOURCE_PATH}" OPTIONS -DBUILD_UNIT_TESTS=OFF)
vcpkg_cmake_install()
file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")
file(INSTALL "${SOURCE_PATH}/LICENSE" DESTINATION "${CURRENT_PACKAGES_DIR}/share/socket-io-client-cpp" RENAME copyright)
'@
Update-Port "socket-io-client-cpp" $sioJson $sioCmake

Write-Host "Final port updated! You are ready to build." -ForegroundColor Green
