#!/bin/bash
# 飞谧车机助手 —— 纯命令行 Android 构建脚本（无 Gradle / 无 AndroidX 依赖）
#
# 交付契约：**对外只交付一个 APK —— 手机端 feimi-phone.apk**（车机端 APK 已内置在它的 assets 里）。
# 车机端 APK 只是构建手机端所必需的中间产物，落在 build/stage/，永不进入 build/out/。
#
# 用法：
#   ./build.sh all      # 默认。车机端 → build/stage → 打进手机端 assets → 只产出 build/out/feimi-phone.apk
#   ./build.sh phone    # 只重编手机端（复用 build/stage 里已有的车机端 APK）
#   ./build.sh car      # 【仅调试】单独编车机端，产物在 build/stage/，不可对外交付
#
# 依赖：Android SDK 的 build-tools（aapt2 / d8 / zipalign / apksigner）与 platform-34/android.jar，
#       按 README 说明放到 build/sdk/ 下即可。除此之外不需要任何第三方库或预编译组件。
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/build/sdk"
BT="$SDK/build-tools"
AJAR="$SDK/platform-34/android.jar"
OUT="$ROOT/build/out"          # 交付目录：只允许出现手机端 APK
STAGE="$ROOT/build/stage"      # 中间产物目录：车机端 APK 等，不对外
WORK="$ROOT/build/work"        # 编译过程目录：classes / dex / 未签名 APK
KS="$ROOT/build/keystore/debug.keystore"
mkdir -p "$OUT" "$STAGE" "$WORK"

for t in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$t" ] || { echo "缺少构建组件: $t（见 README 编译指南）"; exit 1; }
done

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=FeiMi,OU=Dev,O=FeiMi,L=CN,ST=CN,C=CN" >/dev/null 2>&1
  echo "[keystore] 已生成调试签名 $KS"
fi

# build_module <产物名> <模块目录> <输出目录>
build_module() {
  local name="$1" dir="$2" dest="${3:-$STAGE}"
  echo "== 构建 $name =="
  local work="$WORK/$name"
  rm -rf "$work"; mkdir -p "$work/classes" "$work/gen" "$work/dex"

  local resargs=""
  if [ -d "$dir/res" ]; then
    "$BT/aapt2" compile --dir "$dir/res" -o "$work/res.zip" >/dev/null
    resargs="-R $work/res.zip"
  fi

  "$BT/aapt2" link -o "$work/base.apk" -I "$AJAR" \
    --manifest "$dir/AndroidManifest.xml" $resargs \
    --java "$work/gen" --auto-add-overlay >/dev/null

  find "$dir/src" "$work/gen" -name '*.java' > "$work/srcs.txt"
  javac -nowarn -source 8 -target 8 -bootclasspath "$AJAR" -classpath "$AJAR" \
    -encoding UTF-8 -d "$work/classes" @"$work/srcs.txt" 2>&1 \
    | grep -viE "warning|警告|^note|bootstrap" || true
  [ -d "$work/classes/com" ] || { echo "  !! 编译失败"; exit 1; }

  find "$work/classes" -name '*.class' > "$work/classes.txt"
  "$BT/d8" --lib "$AJAR" --min-api 24 --output "$work/dex" @"$work/classes.txt" >/dev/null

  cp "$work/base.apk" "$work/unsigned.apk"
  ( cd "$work/dex" && zip -q -j "$work/unsigned.apk" classes.dex )
  if [ -d "$dir/assets" ]; then
    ( cd "$dir" && zip -q -r "$work/unsigned.apk" assets )
  fi

  "$BT/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
  mkdir -p "$dest"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$dest/$name.apk" "$work/aligned.apk"
  echo "   -> $dest/$name.apk  ($(stat -c%s "$dest/$name.apk") bytes)"
}

# 把车机端中间产物装进手机端 assets（不许手工维护）
stage_car_into_assets() {
  [ -f "$STAGE/feimi-car.apk" ] || { echo "缺少 $STAGE/feimi-car.apk：请先执行 ./build.sh all"; exit 1; }
  mkdir -p "$ROOT/phone/assets"
  cp "$STAGE/feimi-car.apk" "$ROOT/phone/assets/feimi-car.apk"
  echo "[assets] 已内置车机端 APK（$(stat -c%s "$ROOT/phone/assets/feimi-car.apk") bytes）"
}

# 交付把关：out 里只允许有手机端 APK，且手机端确实内置了车机端
verify_delivery() {
  local stray
  stray=$(ls -1 "$OUT" 2>/dev/null | grep -v '^feimi-phone\.apk$' | grep -v '^feimi-phone\.apk\.idsig$' || true)
  if [ -n "$stray" ]; then
    echo "[清理] build/out 中非交付物已移除："
    echo "$stray" | sed 's/^/   - /'
    echo "$stray" | while read -r f; do [ -n "$f" ] && rm -rf "$OUT/$f"; done
  fi
  [ -f "$OUT/feimi-phone.apk" ] || { echo "!! 未产出交付物 $OUT/feimi-phone.apk"; exit 1; }
  unzip -l "$OUT/feimi-phone.apk" | grep -q 'assets/feimi-car.apk' \
    || { echo "!! 手机端 APK 未内置车机端 assets/feimi-car.apk，不可交付"; exit 1; }
  echo
  echo "== 交付产物（唯一）=="
  ls -l "$OUT/feimi-phone.apk"
  echo "（车机端 APK 为中间产物：build/stage/feimi-car.apk，不对外交付）"
}

case "${1:-all}" in
  car)
    build_module feimi-car "$ROOT/car" "$STAGE"
    echo "[提醒] build/stage/feimi-car.apk 是中间产物，仅用于调试，不对外交付"
    ;;
  phone)
    stage_car_into_assets
    build_module feimi-phone "$ROOT/phone" "$OUT"
    verify_delivery
    ;;
  all)
    build_module feimi-car "$ROOT/car" "$STAGE"
    stage_car_into_assets
    build_module feimi-phone "$ROOT/phone" "$OUT"
    verify_delivery
    ;;
  *) echo "用法: $0 [all|phone|car]"; exit 1 ;;
esac
