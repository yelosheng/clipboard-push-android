# Findrapidjson.cmake
find_path(RAPIDJSON_INCLUDE_DIR rapidjson/rapidjson.h)

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(rapidjson DEFAULT_MSG RAPIDJSON_INCLUDE_DIR)

if(rapidjson_FOUND)
    set(RAPIDJSON_INCLUDE_DIRS ${RAPIDJSON_INCLUDE_DIR})
    if(NOT TARGET rapidjson)
        add_library(rapidjson INTERFACE IMPORTED)
        set_target_properties(rapidjson PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${RAPIDJSON_INCLUDE_DIR}"
        )
    endif()
endif()
