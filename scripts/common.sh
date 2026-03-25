#!/bin/bash
# BBM_SCRIPT_VERSION=1.1.1
# Common build functions for JUCE projects
set -e

# Ensure Homebrew paths are in PATH (GUI apps don't inherit shell PATH)
if [ -d "/opt/homebrew/bin" ]; then
  export PATH="/opt/homebrew/bin:$PATH"
fi
if [ -d "/usr/local/bin" ]; then
  export PATH="/usr/local/bin:$PATH"
fi

# Optional: nested JUCE projects (plugin in subdirectory, e.g. CUTMAN/)
SCRIPT_DIR_COMMON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR_COMMON/bbm-project.cfg" ] && . "$SCRIPT_DIR_COMMON/bbm-project.cfg"

# Colors
export RED='\033[0;31m'
export GREEN='\033[0;32m'
export YELLOW='\033[1;33m'
export BLUE='\033[0;34m'
export NC='\033[0m'

print_error() { echo -e "${RED}ERROR: $1${NC}" >&2; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_info() { echo -e "${YELLOW}→ $1${NC}"; }
print_header() { echo -e "${BLUE}═══ $1 ═══${NC}"; }

get_project_root() {
  local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  echo "$(cd "$script_dir/.." && pwd)"
}

get_cmake_source_dir() {
  local root
  root=$(get_project_root)
  if [ -n "${BBM_PLUGIN_SUBDIR:-}" ]; then
    echo "$root/$BBM_PLUGIN_SUBDIR"
  else
    echo "$root"
  fi
}

detect_project_kind() {
  local project_root="$1"
  if [ -f "$project_root/CMakeLists.txt" ] || [ -f "$project_root/CmakeLists.txt" ] || [ -f "$project_root/cmakelists.txt" ]; then
    echo "cmake"
    return 0
  fi
  shopt -s nullglob
  local jucers=("$project_root"/*.jucer)
  shopt -u nullglob
  if [ ${#jucers[@]} -gt 0 ]; then
    echo "projucer"
    return 0
  fi
  if [ -d "$project_root/Builds/MacOSX" ]; then
    shopt -s nullglob
    local xcodes=("$project_root/Builds/MacOSX"/*.xcodeproj)
    shopt -u nullglob
    if [ ${#xcodes[@]} -gt 0 ]; then
      echo "projucer"
      return 0
    fi
  fi
  # Check for root-level .xcodeproj (Airwindows style)
  shopt -s nullglob
  local root_xcodes=("$project_root"/*.xcodeproj)
  shopt -u nullglob
  if [ ${#root_xcodes[@]} -gt 0 ]; then
    echo "airwindows"
    return 0
  fi
  echo "unknown"
}

find_cmake() {
  local cmake_paths=(
    "/opt/homebrew/bin/cmake"
    "/usr/local/bin/cmake"
    "/Applications/CMake.app/Contents/bin/cmake"
    "$(which cmake 2>/dev/null)"
  )
  for path in "${cmake_paths[@]}"; do
    if [ -n "$path" ] && [ -x "$path" ]; then
      echo "$path"
      return 0
    fi
  done
  print_error "CMake not found. Install via: brew install cmake"
  return 1
}

get_cmake() {
  if [ -z "$CMAKE_PATH" ]; then
    CMAKE_PATH=$(find_cmake)
    export CMAKE_PATH
  fi
  echo "$CMAKE_PATH"
}

find_projucer() {
  local projucer_paths=(
    "/Applications/Projucer.app/Contents/MacOS/Projucer"
    "/Applications/JUCE/Projucer.app/Contents/MacOS/Projucer"
    "$(which Projucer 2>/dev/null)"
  )
  for path in "${projucer_paths[@]}"; do
    if [ -n "$path" ] && [ -x "$path" ]; then
      echo "$path"
      return 0
    fi
  done
  print_error "Projucer not found. Install JUCE from https://juce.com"
  return 1
}

get_projucer() {
  if [ -z "$PROJUCER_PATH" ]; then
    PROJUCER_PATH=$(find_projucer)
    export PROJUCER_PATH
  fi
  echo "$PROJUCER_PATH"
}

get_cmake_project_name_and_version() {
  local project_root="$1"
  local cmake_file=""
  if [ -f "$project_root/CMakeLists.txt" ]; then
    cmake_file="$project_root/CMakeLists.txt"
  elif [ -f "$project_root/CmakeLists.txt" ]; then
    cmake_file="$project_root/CmakeLists.txt"
  elif [ -f "$project_root/cmakelists.txt" ]; then
    cmake_file="$project_root/cmakelists.txt"
  else
    print_error "CMakeLists.txt not found"
    return 1
  fi

  # Parse: project(Name VERSION x.y.z)
  local line
  line=$(grep -i "^[[:space:]]*project(" "$cmake_file" | head -n 1)
  if [ -z "$line" ]; then
    print_error "Could not find project(...) line in CMakeLists.txt"
    return 1
  fi

  local name
  local version
  name=$(echo "$line" | sed -E 's/^[[:space:]]*project\(([A-Za-z0-9_+-]+).*/\1/' | tr -d '[:space:]')
  version=$(echo "$line" | sed -E 's/.*VERSION[[:space:]]+([0-9.]+).*/\1/' | tr -d '[:space:])')

  if [ -z "$name" ] || [ -z "$version" ]; then
    print_error "Could not parse project name/version from: $line"
    return 1
  fi

  echo "$name|$version"
}

get_projucer_project_name() {
  local project_root="$1"
  shopt -s nullglob
  local jucers=("$project_root"/*.jucer)
  shopt -u nullglob
  if [ ${#jucers[@]} -eq 0 ]; then
    print_error "No .jucer file found at project root"
    return 1
  fi

  local first_line
  first_line=$(grep -E "<JUCERPROJECT" "${jucers[0]}" | head -n 1)
  if [ -z "$first_line" ]; then
    print_error "Could not find <JUCERPROJECT ...> in ${jucers[0]}"
    return 1
  fi

  local name
  name=$(echo "$first_line" | sed -E 's/.*name="([^"]+)".*/\\1/')
  if [ -z "$name" ] || [ "$name" = "$first_line" ]; then
    # fallback to folder name
    name=$(basename "$project_root")
  fi
  echo "$name"
}

get_juce_version_from_defines() {
  local project_root="$1"
  local defines="$project_root/JuceLibraryCode/JucePluginDefines.h"
  if [ ! -f "$defines" ]; then
    echo ""
    return 0
  fi
  local line
  line=$(grep -E "^[[:space:]]*#define[[:space:]]+JucePlugin_VersionString" "$defines" | head -n 1)
  if [ -z "$line" ]; then
    echo ""
    return 0
  fi
  # Extract "1.2.3"
  echo "$line" | sed -E 's/.*JucePlugin_VersionString[[:space:]]+"([^"]+)".*/\\1/'
}

get_projucer_project_name_and_version() {
  local project_root="$1"
  local name
  local version
  name=$(get_projucer_project_name "$project_root")
  version=$(get_juce_version_from_defines "$project_root")
  if [ -z "$version" ]; then
    version="0.0.0"
  fi
  echo "$name|$version"
}

get_airwindows_project_name_and_version() {
  local project_root="$1"
  local name
  local version="0.0.0"
  
  # Extract project name from folder name
  name=$(basename "$project_root")
  
  # Try to extract version from *Version.h file at root
  shopt -s nullglob
  local version_files=("$project_root"/*[Vv]ersion.h)
  shopt -u nullglob
  if [ ${#version_files[@]} -gt 0 ]; then
    local version_file="${version_files[0]}"
    # Look for version pattern like: #define VERSION "1.0.0" or similar
    local extracted
    extracted=$(grep -E '#define.*[Vv]ersion.*"[0-9]+\\.[0-9]+(\\.[ 0-9]+)?"' "$version_file" | head -n 1 | sed -E 's/.*"([0-9]+\\.[0-9]+(\\.[ 0-9]+)?)".*/\\1/' || true)
    if [ -n "$extracted" ]; then
      version="$extracted"
    else
      # Airwindows hex format: kProjectVersion 0x00010000 (major<<16|minor<<8|patch), skip 0xFFFFFFFF (DEBUG)
      local hex_line
      hex_line=$(grep -E '#define[[:space:]]+k[[:alnum:]]+Version[[:space:]]+0x[0-9a-fA-F]+' "$version_file" | grep -v '0x[Ff][Ff][Ff][Ff][Ff][Ff][Ff][Ff]' | tail -n 1)
      if [ -n "$hex_line" ]; then
        local hex_val
        hex_val=$(echo "$hex_line" | sed -E 's/.*0x([0-9a-fA-F]+).*/\\1/')
        if [ -n "$hex_val" ] && [ "$hex_val" != "$hex_line" ]; then
          local full=$((16#$hex_val))
          local maj=$(((full >> 16) & 0xFF))
          local min=$(((full >> 8) & 0xFF))
          local pat=$((full & 0xFF))
          version="${maj}.${min}.${pat}"
        fi
      fi
    fi
  fi
  
  # If not found at root, check source/ subdirectory
  if [ "$version" = "0.0.0" ] && [ -d "$project_root/source" ]; then
    shopt -s nullglob
    local source_version_files=("$project_root/source"/*[Vv]ersion.h)
    shopt -u nullglob
    if [ ${#source_version_files[@]} -gt 0 ]; then
      local version_file="${source_version_files[0]}"
      local extracted
      extracted=$(grep -E '#define.*[Vv]ersion.*"[0-9]+\\.[0-9]+(\\.[ 0-9]+)?"' "$version_file" | head -n 1 | sed -E 's/.*"([0-9]+\\.[0-9]+(\\.[ 0-9]+)?)".*/\\1/' || true)
      if [ -n "$extracted" ]; then
        version="$extracted"
      fi
    fi
  fi
  
  echo "$name|$version"
}

get_project_name_and_version() {
  local project_root="$1"
  local kind
  kind=$(detect_project_kind "$project_root")
  if [ "$kind" = "cmake" ]; then
    get_cmake_project_name_and_version "$project_root"
    return $?
  fi
  if [ "$kind" = "projucer" ]; then
    get_projucer_project_name_and_version "$project_root"
    return $?
  fi
  if [ "$kind" = "airwindows" ]; then
    get_airwindows_project_name_and_version "$project_root"
    return $?
  fi
  print_error "Could not detect project type (expected CMakeLists.txt, *.jucer, or *.xcodeproj)"
  return 1
}

sanitize_name() {
  # Replace spaces with hyphens (also collapse multiple spaces)
  echo "$1" | tr ' ' '-' | tr -s '-'
}

clean_build() {
  local project_root="$1"
  local build_dir="$project_root/build"
  
  print_info "Cleaning build directory..."
  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  print_success "Build directory cleaned"
}

clean_build_projucer() {
  local project_root="$1"
  local build_dir="$project_root/Builds/MacOSX/build"
  print_info "Cleaning Xcode build directory..."
  rm -rf "$build_dir"
  print_success "Xcode build directory cleaned"
}

clean_build_airwindows() {
  local project_root="$1"
  local build_dir="$project_root/build"
  print_info "Cleaning Airwindows build directory..."
  rm -rf "$build_dir"
  print_success "Build directory cleaned"
}

configure_cmake() {
  local cmake_source="$1"
  local project_root="$2"
  local build_type="$3"
  local version="${4:-0.0.0}"
  local cmake_cmd
  cmake_cmd=$(get_cmake)
  
  print_info "Configuring CMake for $build_type build..."
  print_info "Using CMake: $cmake_cmd"
  
  export BBM_BUILD_TYPE="$build_type"
  export BBM_VERSION="$version"
  "$cmake_cmd" -S "$cmake_source" -B "$project_root/build" -DCMAKE_BUILD_TYPE=Release \
    -DBBM_BUILD_TYPE="$build_type" -DBBM_VERSION="$version"
  print_success "CMake configured"
}

build_plugin() {
  local project_root="$1"
  local cmake_cmd
  cmake_cmd=$(get_cmake)
  
  print_info "Building..."
  "$cmake_cmd" --build "$project_root/build" --config Release -j$(sysctl -n hw.ncpu)
  print_success "Build completed"
}

resave_jucer_project() {
  local project_root="$1"
  
  # Find .jucer file
  shopt -s nullglob
  local jucers=("$project_root"/*.jucer)
  shopt -u nullglob
  
  if [ ${#jucers[@]} -eq 0 ]; then
    print_error "No .jucer file found in project root"
    return 1
  fi
  
  local jucer_file="${jucers[0]}"
  local projucer_cmd
  projucer_cmd=$(get_projucer)
  
  print_info "Generating Xcode project from $(basename "$jucer_file")..."
  "$projucer_cmd" --resave "$jucer_file"
  print_success "Xcode project generated"
}

build_plugin_projucer() {
  local project_root="$1"
  local build_type="$2"
  local version="${3:-0.0.0}"

  # Always regenerate Xcode project from .jucer to keep it in sync
  print_info "Regenerating Xcode project from .jucer file..."
  resave_jucer_project "$project_root" || return 1

  # Find the generated Xcode project
  local xcodeproj=""
  shopt -s nullglob
  local xcodes=("$project_root/Builds/MacOSX"/*.xcodeproj)
  shopt -u nullglob
  if [ ${#xcodes[@]} -gt 0 ]; then
    xcodeproj="${xcodes[0]}"
  fi

  if [ -z "$xcodeproj" ]; then
    print_error "No .xcodeproj found under Builds/MacOSX after generating"
    return 1
  fi

  print_info "Building Xcode project (all targets)..."
  export BBM_BUILD_TYPE="$build_type"
  export BBM_VERSION="$version"
  # Exclude deprecated i386; override legacy deployment targets if needed
  xcodebuild -project "$xcodeproj" -configuration Release -alltargets build ARCHS="x86_64 arm64" MACOSX_DEPLOYMENT_TARGET=10.13
  print_success "Xcode build completed"
}

build_plugin_airwindows() {
  local project_root="$1"
  local build_type="$2"
  local version="${3:-0.0.0}"

  shopt -s nullglob
  local xcodes=("$project_root"/*.xcodeproj)
  shopt -u nullglob
  if [ ${#xcodes[@]} -eq 0 ]; then
    print_error "No .xcodeproj found at project root (Airwindows style)"
    return 1
  fi

  local xcodeproj="${xcodes[0]}"
  print_info "Building Airwindows Xcode project (all targets)..."
  export BBM_BUILD_TYPE="$build_type"
  export BBM_VERSION="$version"
  xcodebuild -project "$xcodeproj" -configuration Release -alltargets build ARCHS="x86_64 arm64" MACOSX_DEPLOYMENT_TARGET=10.13 -sdk macosx
  print_success "Xcode build completed"
}

package_plugins() {
  local project_root="$1"
  local project_name="$2"
  local version="$3"
  local build_type="$4"
  local output_dir="$project_root/releases/$build_type/$version"
  
  print_info "Packaging plugins..."
  mkdir -p "$output_dir"
  
  local safe_name
  safe_name=$(sanitize_name "$project_name")
  
  local cmake_source
  cmake_source=$(get_cmake_source_dir)
  local kind
  kind=$(detect_project_kind "$cmake_source")

  local vst3s=()
  local aus=()
  local aaxs=()
  local apps=()

  if [ "$kind" = "cmake" ]; then
    shopt -s nullglob
    local artefacts_dirs=("$project_root"/build/*_artefacts/Release)
    shopt -u nullglob

    if [ ${#artefacts_dirs[@]} -eq 0 ]; then
      print_info "No JUCE artefacts directory found under build/*_artefacts/Release (skipping packaging)"
      echo "$output_dir"
      return 0
    fi

    local artefacts="${artefacts_dirs[0]}"

    shopt -s nullglob
    vst3s=("$artefacts"/VST3/*.vst3)
    aus=("$artefacts"/AU/*.component)
    aaxs=("$artefacts"/AAX/*.aaxplugin)
    apps=("$artefacts"/Standalone/*.app)
    shopt -u nullglob
  elif [ "$kind" = "projucer" ]; then
    # Projucer/Xcode build output
    local artefacts="$project_root/Builds/MacOSX/build/Release"
    if [ ! -d "$artefacts" ]; then
      print_info "No Xcode Release build output found at Builds/MacOSX/build/Release (skipping packaging)"
      echo "$output_dir"
      return 0
    fi

    # Use find because Projucer output layout varies (may nest under products folder).
    local first_vst3
    local first_au
    local first_aax
    local first_app
    first_vst3=$(find "$artefacts" -maxdepth 4 -name "*.vst3" -print -quit 2>/dev/null || true)
    first_au=$(find "$artefacts" -maxdepth 4 -name "*.component" -print -quit 2>/dev/null || true)
    first_aax=$(find "$artefacts" -maxdepth 4 -name "*.aaxplugin" -print -quit 2>/dev/null || true)
    first_app=$(find "$artefacts" -maxdepth 4 -name "*.app" -print -quit 2>/dev/null || true)
    if [ -n "$first_vst3" ]; then vst3s=("$first_vst3"); fi
    if [ -n "$first_au" ]; then aus=("$first_au"); fi
    if [ -n "$first_aax" ]; then aaxs=("$first_aax"); fi
    if [ -n "$first_app" ]; then apps=("$first_app"); fi
  elif [ "$kind" = "airwindows" ]; then
    # Airwindows build output
    local artefacts="$project_root/build/Release"
    if [ ! -d "$artefacts" ]; then
      print_info "No Airwindows Release build output found at build/Release (skipping packaging)"
      echo "$output_dir"
      return 0
    fi

    # Use find because Airwindows output may vary (VST2, AU, etc.)
    local first_vst
    local first_vst3
    local first_au
    local first_aax
    local first_app
    first_vst=$(find "$artefacts" -maxdepth 4 -name "*.vst" -print -quit 2>/dev/null || true)
    first_vst3=$(find "$artefacts" -maxdepth 4 -name "*.vst3" -print -quit 2>/dev/null || true)
    first_au=$(find "$artefacts" -maxdepth 4 -name "*.component" -print -quit 2>/dev/null || true)
    first_aax=$(find "$artefacts" -maxdepth 4 -name "*.aaxplugin" -print -quit 2>/dev/null || true)
    first_app=$(find "$artefacts" -maxdepth 4 -name "*.app" -print -quit 2>/dev/null || true)
    # Prefer VST2 (.vst) for Airwindows, but also support VST3 if available
    if [ -n "$first_vst" ]; then vst3s=("$first_vst"); fi
    if [ -n "$first_vst3" ]; then vst3s=("$first_vst3"); fi
    if [ -n "$first_au" ]; then aus=("$first_au"); fi
    if [ -n "$first_aax" ]; then aaxs=("$first_aax"); fi
    if [ -n "$first_app" ]; then apps=("$first_app"); fi
  fi
  
  if [ ${#vst3s[@]} -gt 0 ]; then
    # When multiple .vst3 exist (e.g. Doss Effect.vst3 and Doss Effect-1.1.0-alpha.vst3),
    # package only ONE: for dev/alpha prefer the versioned one if present, otherwise rename the first
    local vst3_to_package
    local already_versioned=0
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local versioned_pattern="*-${version}-${build_type}.vst3"
      vst3_to_package=""
      for v in "${vst3s[@]}"; do
        if [[ "$(basename "$v")" == $versioned_pattern ]]; then
          vst3_to_package="$v"
          already_versioned=1
          break
        fi
      done
      [ -z "$vst3_to_package" ] && vst3_to_package="${vst3s[0]}"
    else
      vst3_to_package="${vst3s[0]}"
    fi
    local base
    base=$(basename "$vst3_to_package")
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local final_name
      if [ "$already_versioned" -eq 1 ]; then
        final_name="$base"
      else
        local ext="${base##*.}"
        local stem="${base%.*}"
        final_name="${stem}-${version}-${build_type}.${ext}"
      fi
      local tmpdir
      tmpdir=$(mktemp -d)
      cp -R "$vst3_to_package" "$tmpdir/$final_name"
      (cd "$tmpdir" && zip -r "$output_dir/${safe_name}-VST3-${version}-${build_type}.zip" "$final_name")
      rm -rf "$tmpdir"
    else
      (cd "$(dirname "$vst3_to_package")" && zip -r "$output_dir/${safe_name}-VST3-${version}-${build_type}.zip" "$base")
    fi
    print_success "VST3 packaged"
  fi
  
  if [ ${#aus[@]} -gt 0 ]; then
    local au_to_package
    local au_already_versioned=0
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local versioned_pattern="*-${version}-${build_type}.component"
      for a in "${aus[@]}"; do
        if [[ "$(basename "$a")" == $versioned_pattern ]]; then
          au_to_package="$a"
          au_already_versioned=1
          break
        fi
      done
      [ -z "$au_to_package" ] && au_to_package="${aus[0]}"
    else
      au_to_package="${aus[0]}"
    fi
    local base
    base=$(basename "$au_to_package")
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local final_name
      [ "$au_already_versioned" -eq 1 ] && final_name="$base" || final_name="${base%.*}-${version}-${build_type}.component"
      local tmpdir
      tmpdir=$(mktemp -d)
      cp -R "$au_to_package" "$tmpdir/$final_name"
      (cd "$tmpdir" && zip -r "$output_dir/${safe_name}-AU-${version}-${build_type}.zip" "$final_name")
      rm -rf "$tmpdir"
    else
      (cd "$(dirname "$au_to_package")" && zip -r "$output_dir/${safe_name}-AU-${version}-${build_type}.zip" "$base")
    fi
    print_success "AU packaged"
  fi
  
  if [ ${#aaxs[@]} -gt 0 ]; then
    local aax_to_package
    local aax_already_versioned=0
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local versioned_pattern="*-${version}-${build_type}.aaxplugin"
      for a in "${aaxs[@]}"; do
        if [[ "$(basename "$a")" == $versioned_pattern ]]; then
          aax_to_package="$a"
          aax_already_versioned=1
          break
        fi
      done
      [ -z "$aax_to_package" ] && aax_to_package="${aaxs[0]}"
    else
      aax_to_package="${aaxs[0]}"
    fi
    local base
    base=$(basename "$aax_to_package")
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local final_name
      [ "$aax_already_versioned" -eq 1 ] && final_name="$base" || final_name="${base%.*}-${version}-${build_type}.aaxplugin"
      local tmpdir
      tmpdir=$(mktemp -d)
      cp -R "$aax_to_package" "$tmpdir/$final_name"
      (cd "$tmpdir" && zip -r "$output_dir/${safe_name}-AAX-${version}-${build_type}.zip" "$final_name")
      rm -rf "$tmpdir"
    else
      (cd "$(dirname "$aax_to_package")" && zip -r "$output_dir/${safe_name}-AAX-${version}-${build_type}.zip" "$base")
    fi
    print_success "AAX packaged"
  fi
  
  if [ ${#apps[@]} -gt 0 ]; then
    local app_to_package
    local app_already_versioned=0
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local versioned_pattern="*-${version}-${build_type}.app"
      for a in "${apps[@]}"; do
        if [[ "$(basename "$a")" == $versioned_pattern ]]; then
          app_to_package="$a"
          app_already_versioned=1
          break
        fi
      done
      [ -z "$app_to_package" ] && app_to_package="${apps[0]}"
    else
      app_to_package="${apps[0]}"
    fi
    local base
    base=$(basename "$app_to_package")
    if [ "$build_type" = "dev" ] || [ "$build_type" = "alpha" ]; then
      local final_name
      [ "$app_already_versioned" -eq 1 ] && final_name="$base" || final_name="${base%.*}-${version}-${build_type}.app"
      local tmpdir
      tmpdir=$(mktemp -d)
      cp -R "$app_to_package" "$tmpdir/$final_name"
      (cd "$tmpdir" && zip -r "$output_dir/${safe_name}-Standalone-${version}-${build_type}.zip" "$final_name")
      rm -rf "$tmpdir"
    else
      (cd "$(dirname "$app_to_package")" && zip -r "$output_dir/${safe_name}-Standalone-${version}-${build_type}.zip" "$base")
    fi
    print_success "Standalone packaged"
  fi
  
  print_success "Artifacts packaged to $output_dir"
  echo "$output_dir"
}