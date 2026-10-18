cmake_minimum_required(VERSION 3.22.1)
project(txaguard C)

# Bộ bắt crash native của TxTelex — chỉ libc/libdl/liblog, không phụ thuộc gì thêm.
add_library(txaguard SHARED txa_guard.c)
target_compile_options(txaguard PRIVATE -Wall -Wextra -O2 -fvisibility=hidden -fno-omit-frame-pointer)
target_link_libraries(txaguard log dl)
