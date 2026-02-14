#----------------------------------------------------------------
# Generated CMake target import file for configuration "Debug".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator" for configuration "Debug"
set_property(TARGET unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator APPEND PROPERTY IMPORTED_CONFIGURATIONS DEBUG)
set_target_properties(unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator PROPERTIES
  IMPORTED_LINK_INTERFACE_LANGUAGES_DEBUG "CXX"
  IMPORTED_LOCATION_DEBUG "${_IMPORT_PREFIX}/debug/lib/nayuki-qr-code-generator.lib"
  )

list(APPEND _cmake_import_check_targets unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator )
list(APPEND _cmake_import_check_files_for_unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator "${_IMPORT_PREFIX}/debug/lib/nayuki-qr-code-generator.lib" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
