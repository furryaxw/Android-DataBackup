cmake_minimum_required(VERSION 3.22.1)

project("native")

add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

add_subdirectory(nativelib)

find_program(CARGO_EXECUTABLE cargo)
if(CARGO_EXECUTABLE)
    add_subdirectory(rustic)
else()
    message(STATUS "Rust toolchain not found; rustic repository backend will not be built.")
endif()

# add_subdirectory(external)
