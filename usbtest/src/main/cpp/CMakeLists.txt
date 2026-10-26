cmake_minimum_required(VERSION 3.18)
project(isopump C)

add_library(isopump SHARED iso_pump.c)
target_compile_options(isopump PRIVATE -Wall -O2)
target_link_libraries(isopump log)
