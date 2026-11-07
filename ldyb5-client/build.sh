#!/bin/bash
# R1 小智客户端 —— 构建脚本（aapt2 + d8，无 Gradle）
set -e

PROJ="${PROJ:-/workspace/ldyb5-client}"
SDK="${SDK:-/opt/android-sdk}"
OUT="${OUT:-/tmp/r1build}"
AAPT2=$SDK/bin/aapt2
R8=$SDK/r8.jar
ANDROID_JAR=$SDK/android.jar
ORGJSON="${ORGJSON:-/tmp/dl/orgjson.jar}"
KEYSTORE="${KEYSTORE:-$PROJ/debug.keystore}"
VER="${VER:-v0.1.0-ldyb5}"
JAVA="${JAVA:-$(which java)}"

LIBS=$PROJ/libs
CP="$ANDROID_JAR:$ORGJSON:$LIBS/sherpa-onnx-classes.jar:$LIBS/kotlin-stdlib.jar:$LIBS/okhttp.jar:$LIBS/okio.jar"

# assets 可选：v1.2.3 起 KWS 停用（MODE_BUS_ONLY），不再需要 assets/kws/*.onnx。
# 若目录不存在就建空占位，否则 aapt2 link 会因 -A 指向不存在的路径而失败。
if [ ! -d "$PROJ/assets" ]; then
  echo "note: $PROJ/assets 不存在，创建空目录（v1.2.3 不需要 KWS 模型）"
  mkdir -p "$PROJ/assets"
fi

rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex $OUT/gen

echo "=== [1/6] javac ==="
find $PROJ/src -name "*.java" > $OUT/sources.txt
javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
  -classpath "$CP" -d $OUT/classes @$OUT/sources.txt
echo "javac OK, class count: $(find $OUT/classes -name '*.class' | wc -l)"
find $OUT/classes -name "*.class" | head -20

echo "=== [2/6] classes.jar ==="
( cd $OUT/classes && jar cf $OUT/app-classes.jar . )

echo "=== [3/6] d8 ==="
# org.json 只做编译期依赖（Android 运行时自带），不进 dex
$JAVA -cp $R8 com.android.tools.r8.D8 \
  --release --min-api 21 \
  --lib $ANDROID_JAR \
  --lib $ORGJSON \
  --output $OUT/dex \
  $OUT/app-classes.jar \
  $LIBS/sherpa-onnx-classes.jar \
  $LIBS/kotlin-stdlib.jar \
  $LIBS/okhttp.jar \
  $LIBS/okio.jar 2>&1 | grep -viE "^Warning|Type .* was not found|desugaring" || true

echo "=== [4/6] aapt2 link ==="
$AAPT2 link \
  -I $ANDROID_JAR \
  --manifest $PROJ/AndroidManifest.xml \
  -A $PROJ/assets \
  --java $OUT/gen \
  --debug-mode \
  -o $OUT/base.apk

echo "=== [5/6] 合入 dex + so ==="
# 不依赖 zip 命令（部分系统没有 / 被坏垫片顶替）。
# 用 python3 的 zipfile：把 classes.dex 和 lib/ 追加进 aapt2 产出的 base.apk。
STAGE=$OUT/stage
rm -rf $STAGE
mkdir -p $STAGE/lib/armeabi-v7a
cp $OUT/dex/classes.dex $STAGE/classes.dex
cp $PROJ/jniLibs/armeabi-v7a/*.so $STAGE/lib/armeabi-v7a/
cp $OUT/base.apk $OUT/unsigned.apk
python3 - "$OUT/unsigned.apk" "$STAGE" <<'PYEOF'
import sys, os, zipfile
apk, stage = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    for dp, dn, fn in os.walk(stage):
        for f in sorted(fn):
            full = os.path.join(dp, f)
            z.write(full, os.path.relpath(full, stage))
PYEOF

echo "=== [6/6] 签名 ==="
OUTAPK=$PROJ/ldyb5-client-$VER.apk
rm -f $OUTAPK
cp $OUT/unsigned.apk $OUTAPK
jarsigner -keystore $KEYSTORE -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 \
  $OUTAPK androiddebugkey >/dev/null

# 强制校验签名真的写进去了（曾出现过静默失败：APK 无 META-INF 却无人察觉）
if ! unzip -l $OUTAPK | grep -q "META-INF/.*\.RSA"; then
  echo "!!! 签名失败：APK 内没有 META-INF/*.RSA，安装会失败" >&2
  exit 1
fi

echo
echo "=== 完成 ==="
ls -la $OUTAPK
echo "--- 内容 ---"
unzip -l $OUTAPK | grep -E "classes.dex|\.so|AndroidManifest|assets/"
