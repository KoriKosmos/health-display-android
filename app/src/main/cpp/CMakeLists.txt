cmake_minimum_required(VERSION 3.22)
project(discord_bridge CXX)
set(CMAKE_CXX_STANDARD 17)

# Provided through Prefab by discord_partner_sdk.aar (third_party/discord_social_sdk/).
find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(discord_bridge SHARED discord_bridge.cpp)
target_link_libraries(discord_bridge discord_partner_sdk::discord_partner_sdk log)
