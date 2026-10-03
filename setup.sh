#!/usr/bin/env bash
# Fetches ImGui + the AML mod headers and patches ImGui so its symbols stay private.
set -euo pipefail
cd "$(dirname "$0")"

IMGUI_TAG="${IMGUI_TAG:-v1.91.9}"

rm -rf jni/imgui aml
git clone --depth 1 --branch "$IMGUI_TAG" https://github.com/ocornut/imgui jni/imgui
git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/RusJJ/AndroidModLoader aml

# AML headers must be reachable as <mod/amlmod.h>
mkdir -p jni/include
rm -rf jni/include/mod
cp -r aml/mod jni/include/mod

# keep ImGui symbols hidden (the original ARM CheatMenu exports its own ImGui copy)
printf '\n#define IMGUI_API __attribute__((visibility("hidden")))\n' >> jni/imgui/imconfig.h

# extra include dirs some AML headers need (gloss.h etc.)
INC=""
for d in aml/include aml/ARMPatch aml/ARMPatch/include; do
  if [ -d "$d" ]; then INC="$INC \$(LOCAL_PATH)/../$d"; fi
done
G="$(find aml -name gloss.h -print -quit || true)"
if [ -n "$G" ]; then INC="$INC \$(LOCAL_PATH)/../$(dirname "$G")"; fi
echo "AML_EXTRA_INC :=$INC" > jni/aml_inc.mk

echo "--- AML mod/ folder:"; ls jni/include/mod
echo "OK: imgui $IMGUI_TAG + AML headers ready"
