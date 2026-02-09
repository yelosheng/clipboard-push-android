# Findwebsocketpp.cmake
# Finds the websocketpp library.
#
# This header-only library is searched.
#
# The following variables are set:
# WEBSOCKETPP_FOUND - True if websocketpp was found.
# WEBSOCKETPP_INCLUDE_DIR - The includes directory.

find_path(WEBSOCKETPP_INCLUDE_DIR websocketpp/version.hpp)

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(websocketpp DEFAULT_MSG WEBSOCKETPP_INCLUDE_DIR)

if(websocketpp_FOUND)
    set(WEBSOCKETPP_INCLUDE_DIRS ${WEBSOCKETPP_INCLUDE_DIR})
    if(NOT TARGET websocketpp::websocketpp)
        add_library(websocketpp::websocketpp INTERFACE IMPORTED)
        set_target_properties(websocketpp::websocketpp PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${WEBSOCKETPP_INCLUDE_DIR}"
        )
    endif()
endif()
