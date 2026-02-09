# Findasio.cmake
find_path(ASIO_INCLUDE_DIR asio.hpp)

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(asio DEFAULT_MSG ASIO_INCLUDE_DIR)

if(asio_FOUND)
    set(ASIO_INCLUDE_DIRS ${ASIO_INCLUDE_DIR})
    if(NOT TARGET asio::asio)
        add_library(asio::asio INTERFACE IMPORTED)
        set_target_properties(asio::asio PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${ASIO_INCLUDE_DIR}"
        )
    endif()
endif()
