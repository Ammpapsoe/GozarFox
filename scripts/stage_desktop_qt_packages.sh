#!/usr/bin/env bash
# Build the current sources without rewriting version declarations or changing
# the official release workflow. All output stays in temporary runner paths.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
qt="$RUNNER_TEMP/production-layout-qt/gozar-original-ui"
core="$RUNNER_TEMP/same-commit/gozar-core"
out="$RUNNER_TEMP/packages"
cd "$root/desktop_flutter"
flutter pub get
platform=$GOOS
if [[ "$platform" == darwin ]]; then platform=macos; fi
flutter build "$platform" --release --dart-define=GOZAR_EXPERIMENTAL_ORIGINAL_QT=true
version=$(sed -nE 's/^version: ([0-9]+\.[0-9]+\.[0-9]+)\+.*/\1/p' pubspec.yaml)
[[ -n "$version" ]]
case "$GOOS" in
  windows)
    runner=$(find build/windows -path '*/runner/Release/gozar.exe' -print -quit)
    [[ -n "$runner" ]]
    folder="$RUNNER_TEMP/desktop-qt-package-stage"
    mkdir "$folder"
    cp -a "$(dirname "$runner")/." "$folder/"
    find "$folder" -type f \( -iname '*.pdb' -o -iname '*.lib' -o -iname '*.exp' \) -delete
    cp "$RUNNER_TEMP/production-layout-qt/Release/gozar-original-ui.exe" "$folder/"
    cp "$core.exe" "$folder/gozar-core.exe"
    cmp "$core.exe" "$folder/gozar-core.exe"
    windeployqt --release --no-translations --no-compiler-runtime --dir "$folder" "$folder/gozar-original-ui.exe"
    bin="$folder"; notices="$folder" ;;
  linux)
    folder="$PWD/build/linux/x64/release/bundle"
    cp "$qt" "$folder/"
    cp "$core" "$folder/"
    cmp "$core" "$folder/gozar-core"
    mkdir -p "$folder/qt/lib" "$folder/qt/plugins"
    cp -a "$QT_ROOT_DIR"/lib/libQt6*.so* "$folder/qt/lib/"
    for name in "$QT_ROOT_DIR"/lib/libicu*.so* "$QT_ROOT_DIR"/lib/libxcb*.so*; do
      if [[ -e "$name" ]]; then cp -a "$name" "$folder/qt/lib/"; fi
    done
    # Ship platform support and only the image/icon plugin categories.
    for name in platforms imageformats iconengines xcbglintegrations platformthemes; do
      if [[ -d "$QT_ROOT_DIR/plugins/$name" ]]; then cp -a "$QT_ROOT_DIR/plugins/$name" "$folder/qt/plugins/"; fi
    done
    patchelf --set-rpath '$ORIGIN/qt/lib' "$folder/gozar-original-ui"
    printf '[Paths]\nPrefix = qt\nLibraries = lib\nPlugins = plugins\n' > "$folder/qt.conf"
    bin="$folder"; notices="$folder" ;;
  darwin)
    app="$PWD/build/macos/Build/Products/Release/Gozar.app"
    bin="$app/Contents/MacOS"; notices="$app/Contents/Resources"
    cp "$qt" "$bin/"
    cp "$core" "$bin/"
    cmp "$core" "$bin/gozar-core"
    arch="$GOARCH"; if [[ "$arch" == amd64 ]]; then arch=x86_64; fi
    for binary in "$bin/Gozar" "$bin/gozar-original-ui" "$bin/gozar-core" "$app"/Contents/Frameworks/*.framework/Versions/A/*; do
      [[ -f "$binary" ]] || continue
      if ! lipo -archs "$binary" >/dev/null 2>&1; then continue; fi
      if [[ "$(lipo -archs "$binary")" != "$arch" ]]; then
        lipo -thin "$arch" "$binary" -output "$binary.thin"
        chmod 755 "$binary.thin"
        mv "$binary.thin" "$binary"
      fi
    done
    macdeployqt "$app" -executable="$bin/gozar-original-ui"
    for binary in "$bin/Gozar" "$bin/gozar-original-ui" "$bin/gozar-core"; do
      [[ "$(lipo -archs "$binary")" == "$arch" ]]
    done ;;
  *) exit 2 ;;
esac
cp "$root"/{LICENSE,NOTICE,WINTUN-LICENSE.txt,QUIC-GO-LICENSE.txt,QPACK-LICENSE.txt,GO-DEPS-LICENSES.txt} "$notices/"
cp "$root/desktop_qt_adapter/vendor/windscribe/LICENSE" "$notices/WINDSCRIBE-GPL-2-LICENSE.txt"
cp "$root/desktop_qt_adapter/branding/VAZIRMATN-OFL.txt" "$notices/"
# Validate a temporary, extracted bundle, not a binary that can accidentally
# borrow modules from the build location. No real account is used.
case "$GOOS" in
  linux)
    tar -C "$(dirname "$folder")" -cJf "$out/Gozar-Linux-amd64.tar.xz" bundle
    (cd "$root" && bash scripts/package_deb.sh "$folder" "$out/Gozar-Linux-amd64.deb" "$version")
    mkdir "$RUNNER_TEMP/desktop-extracted"
    tar -xJf "$out/Gozar-Linux-amd64.tar.xz" -C "$RUNNER_TEMP/desktop-extracted"
    dpkg-deb -x "$out/Gozar-Linux-amd64.deb" "$RUNNER_TEMP/desktop-extracted"
    for extracted in "$RUNNER_TEMP/desktop-extracted/bundle" "$RUNNER_TEMP/desktop-extracted/usr/lib/gozar"; do
      cmp "$core" "$extracted/gozar-core"
      env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH -u LD_LIBRARY_PATH QT_QPA_PLATFORM=offscreen \
        timeout 60s "$extracted/gozar-original-ui" --ci-signed-out
    done ;;
  darwin)
    # Ad-hoc validates bundle layout only. This is not Developer ID/notarization.
    codesign --force --deep --sign - "$app"
    python "$root/scripts/archive_macos.py" "$app" "$out/Gozar-$PACKAGE_LABEL.tar.xz"
    mkdir "$RUNNER_TEMP/desktop-extracted"
    tar -xJf "$out/Gozar-$PACKAGE_LABEL.tar.xz" -C "$RUNNER_TEMP/desktop-extracted"
    extracted="$RUNNER_TEMP/desktop-extracted/Gozar.app"
    codesign --verify --deep --strict "$extracted"
    env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH -u DYLD_LIBRARY_PATH QT_QPA_PLATFORM=offscreen \
      "$extracted/Contents/MacOS/gozar-original-ui" --ci-signed-out ;;
esac
