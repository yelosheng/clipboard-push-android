# Clipboard Man - vcpkg Fix Script
$vcpkgRoot = "D:/vcpkg"
$portsDir = "$vcpkgRoot/ports"

function Create-Port {
    param($name, $json, $cmake)
    $path = "$portsDir/$name"
    if (!(Test-Path $path)) {
        Write-Host "Creating port: $name" -ForegroundColor Cyan
        New-Item -ItemType Directory -Path $path -Force | Out-Null
        Set-Content -Path "$path/vcpkg.json" -Value $json -Encoding UTF8
        Set-Content -Path "$path/portfile.cmake" -Value $cmake -Encoding UTF8
    } else {
        Write-Host "Port $name already exists, skipping." -ForegroundColor Green
    }
}

# --- 1. asio ---
$asioJson = @'
{
  "name": "asio",
  "version": "1.30.2",
  "description": "Asio C++ library for network and low-level I/O programming",
  "homepage": "https://think-async.com/Asio/",
  "license": "BSL-1.0"
}
'@
$asioCmake = @'
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO chriskohlhoff/asio
    REF asio-1-30-2
    SHA512 afb3e414c5147be86422730ca78189670d9e1c75010629a8a6142c6734139f40f0f421f64973305a415951d6c8b355227f272c72b8d5a7146e29705a6988849b
    HEAD_REF master
)
file(INSTALL "${SOURCE_PATH}/asio/include/" DESTINATION "${CURRENT_PACKAGES_DIR}/include")
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE_1_0.txt")
'@
Create-Port "asio" $asioJson $asioCmake

# --- 2. websocketpp ---
$wsJson = @'
{
  "name": "websocketpp",
  "version": "0.8.2",
  "description": "Library for WebSocket clients and servers",
  "homepage": "https://www.zaphoyd.com/websocketpp",
  "license": "BSD-3-Clause",
  "dependencies": ["asio"]
}
'@
$wsCmake = @'
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO zaphoyd/websocketpp
    REF 0.8.2
    SHA512 6965427d1109062f83134372a6b281f62117c4627b003a27f677598c4f0b09426f432575a743b12384a222383818610f4435b80424565780280f9797240c069b
    HEAD_REF master
)
set(VCPKG_POLICY_DLL_HELPER enabled)
file(INSTALL "${SOURCE_PATH}/websocketpp" DESTINATION "${CURRENT_PACKAGES_DIR}/include")
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/COPYING")
'@
Create-Port "websocketpp" $wsJson $wsCmake

# --- 3. socket-io-client-cpp ---
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
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
'@
Create-Port "socket-io-client-cpp" $sioJson $sioCmake

Write-Host "`nAll essential ports checked/created successfully!" -ForegroundColor Green
Write-Host "Now you can run the cmake command again." -ForegroundColor Yellow
