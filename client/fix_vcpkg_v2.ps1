# Clipboard Man - vcpkg Fix Script V2
$vcpkgRoot = "D:/vcpkg"
$portsDir = "$vcpkgRoot/ports"

# 1. 彻底移除之前手动创建的、可能有问题的目录
$targetPorts = @("asio", "websocketpp", "socket-io-client-cpp")
foreach ($p in $targetPorts) {
    if (Test-Path "$portsDir/$p") {
        Remove-Item -Recurse -Force "$portsDir/$p"
    }
}

Write-Host "Old ports removed. Re-creating with official structure..." -ForegroundColor Cyan

# 2. 重新创建 websocketpp (使用更兼容的配置)
$wsPath = "$portsDir/websocketpp"
New-Item -ItemType Directory -Path $wsPath -Force | Out-Null
Set-Content -Path "$wsPath/vcpkg.json" -Value (@'
{
  "name": "websocketpp",
  "version": "0.8.2",
  "description": "Library for WebSocket clients and servers",
  "homepage": "https://www.zaphoyd.com/websocketpp",
  "license": "BSD-3-Clause"
}
'@) -Encoding UTF8
Set-Content -Path "$wsPath/portfile.cmake" -Value (@'
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO zaphoyd/websocketpp
    REF 0.8.2
    SHA512 6965427d1109062f83134372a6b281f62117c4627b003a27f677598c4f0b09426f432575a743b12384a222383818610f4435b80424565780280f9797240c069b
    HEAD_REF master
)
file(COPY "${SOURCE_PATH}/websocketpp" DESTINATION "${CURRENT_PACKAGES_DIR}/include")
file(INSTALL "${SOURCE_PATH}/COPYING" DESTINATION "${CURRENT_PACKAGES_DIR}/share/websocketpp" RENAME copyright)
'@) -Encoding UTF8

# 3. 重新创建 socket-io-client-cpp
$sioPath = "$portsDir/socket-io-client-cpp"
New-Item -ItemType Directory -Path $sioPath -Force | Out-Null
Set-Content -Path "$sioPath/vcpkg.json" -Value (@'
{
  "name": "socket-io-client-cpp",
  "version": "3.1.0",
  "dependencies": ["websocketpp", "rapidjson"]
}
'@) -Encoding UTF8
Set-Content -Path "$sioPath/portfile.cmake" -Value (@'
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
'@) -Encoding UTF8

Write-Host "Ports re-created. Please try cmake again." -ForegroundColor Green
