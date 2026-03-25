#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

PROJECT_ROOT=$(get_project_root)
CMAKE_SOURCE=$(get_cmake_source_dir)
BUILD_TYPE="dev"

print_header "Development Build"

info=$(get_project_name_and_version "$CMAKE_SOURCE")
PROJECT_NAME="${info%%|*}"
VERSION="${info##*|}"
print_info "Project: $PROJECT_NAME"
print_info "Version: $VERSION"

KIND=$(detect_project_kind "$CMAKE_SOURCE")
if [ "$KIND" = "cmake" ]; then
  clean_build "$PROJECT_ROOT"
  configure_cmake "$CMAKE_SOURCE" "$PROJECT_ROOT" "$BUILD_TYPE" "$VERSION"
  build_plugin "$PROJECT_ROOT"
elif [ "$KIND" = "projucer" ]; then
  clean_build_projucer "$PROJECT_ROOT"
  build_plugin_projucer "$PROJECT_ROOT" "$BUILD_TYPE" "$VERSION"
elif [ "$KIND" = "airwindows" ]; then
  clean_build_airwindows "$PROJECT_ROOT"
  build_plugin_airwindows "$PROJECT_ROOT" "$BUILD_TYPE" "$VERSION"
else
  print_error "Unknown project type"
  exit 1
fi

OUTPUT_DIR=$(package_plugins "$PROJECT_ROOT" "$PROJECT_NAME" "$VERSION" "$BUILD_TYPE")

print_header "Development Build Complete"
print_success "Output: $OUTPUT_DIR"
exit 0