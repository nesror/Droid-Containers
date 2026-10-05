cmake_minimum_required(VERSION 3.22)
project(pty C)

add_library(pty SHARED pty.c)
target_link_libraries(pty PRIVATE log)
target_compile_options(pty PRIVATE -Wall -Wextra -O2)
