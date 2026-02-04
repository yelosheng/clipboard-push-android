# Clipboard Man - vcpkg Fix Script V3 (Updated Hash)
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

# --- 1. websocketpp (使用你机器反馈的正确哈希值) ---
$wsJson = @'
{
  "name": "websocketpp",
  "version": "0.8.2",
  "description": "Library for WebSocket clients and servers",
  "homepage": "https://www.zaphoyd.com/websocketpp",
  "license": "BSD-3-Clause"
}
'@
$wsCmake = @'
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO zaphoyd/websocketpp
    REF 0.8.2
    SHA512 b2afc63edb69ce81a3a6c06b3d857b3e8820f0e22300ac32bb20ab30ff07bd58bd5ada3e526ed8ab52de934e0e3a26cad2118b0e68ecf3e5e9e8d7101348fd06
    HEAD_REF master
)
file(COPY "${SOURCE_PATH}/websocketpp" DESTINATION "${CURRENT_PACKAGES_DIR}/include")
file(INSTALL "${SOURCE_PATH}/COPYING" DESTINATION "${CURRENT_PACKAGES_DIR}/share/websocketpp" RENAME copyright)
'@
Update-Port "websocketpp" $wsJson $wsCmake

# --- 2. socket-io-client-cpp ---
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
    SHA512 85662705001768853609805908687770851897368537557004381867167732860883134606254421111003450004245781525046274710183377770281655655
)
vcpkg_cmake_configure(SOURCE_PATH "${SOURCE_PATH}" OPTIONS -DBUILD_UNIT_TESTS=OFF)
vcpkg_cmake_install()
file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")
file(INSTALL "${SOURCE_PATH}/LICENSE" DESTINATION "${CURRENT_PACKAGES_DIR}/share/socket-io-client-cpp" RENAME copyright)
'@
Update-Port "socket-io-client-cpp" $sioJson $sioCmake

Write-Host "Ports updated with the correct hash. Try cmake again!" -ForegroundColor Green
