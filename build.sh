#!/bin/sh
# 构建 ColorOS 堆叠信号
#
# 依赖环境变量：
#   ANDROID_JAR         android.jar（compileSdk 36）
#   LIBXP_JAR           libxposed API 102 的接口 jar
#   KEYSTORE            签名 keystore
# 可选：
#   ANDROID_BUILD_TOOLS build-tools 目录（默认 $ANDROID_HOME/build-tools/36.0.0）
#   KS_ALIAS/KS_PASS/KS_KEYPASS  签名别名与密码（默认 android/android）
#   JAVA / JAVAC / D8 / APKSIGNER / AAPT / ZIPALIGN  单独覆盖工具路径
#
# 例：
#   ANDROID_JAR=$HOME/android.jar LIBXP_JAR=libs/libxposed-api-102.jar \
#   KEYSTORE=debug.keystore sh build.sh /tmp/out
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
SRC=$ROOT/src
META=$ROOT/meta
OUT=$1
[ -z "$OUT" ] && OUT=$ROOT/build

[ -z "$ANDROID_JAR" ] && { echo 'ANDROID_JAR 未设置'; exit 1; }
[ -z "$LIBXP_JAR" ] && { echo 'LIBXP_JAR 未设置'; exit 1; }
[ -z "$KEYSTORE" ] && { echo 'KEYSTORE 未设置'; exit 1; }
[ -z "$KS_ALIAS" ] && KS_ALIAS=android
[ -z "$KS_PASS" ] && KS_PASS=android
[ -z "$KS_KEYPASS" ] && KS_KEYPASS=$KS_PASS

# 工具链：兼容 build-tools 的 bin/ 与扁平两种布局
BT=$ANDROID_BUILD_TOOLS
[ -z "$BT" ] && BT=$ANDROID_HOME/build-tools/36.0.0
[ -x "$BT/bin/aapt" ] && BT=$BT/bin
[ -z "$JAVA" ] && JAVA=java
[ -z "$JAVAC" ] && JAVAC=javac
if [ -z "$D8" ]; then
    [ -f "$BT/lib/d8.jar" ] && D8=$BT/lib/d8.jar
    [ -f "$BT/../share/java/d8.jar" ] && D8=$BT/../share/java/d8.jar
fi
if [ -z "$APKSIGNER" ]; then
    [ -f "$BT/lib/apksigner.jar" ] && APKSIGNER=$BT/lib/apksigner.jar
    [ -f "$BT/../share/java/apksigner.jar" ] && APKSIGNER=$BT/../share/java/apksigner.jar
fi
[ -z "$D8" ] && { echo '找不到 d8.jar，请用 D8 指定'; exit 1; }
[ -z "$APKSIGNER" ] && { echo '找不到 apksigner.jar，请用 APKSIGNER 指定'; exit 1; }
[ -z "$AAPT" ] && AAPT=$BT/aapt
# aapt 需要 framework 资源表才能解析 android:xxx 属性
[ -z "$FRAMEWORK_RES" ] && FRAMEWORK_RES=/system/framework/framework-res.apk
[ -z "$ZIPALIGN" ] && ZIPALIGN=$BT/zipalign

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] javac"
$JAVAC -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$LIBXP_JAR" -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" $(find "$SRC" -name '*.java')

echo "[2/5] d8"
$JAVA -cp "$D8" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "[3/5] resources"
$AAPT package -f -M "$META/AndroidManifest.xml" -S "$META/res" \
    -I "$FRAMEWORK_RES" -F "$OUT/base.apk"
cd "$OUT" && cp -r "$META/META-INF" . && cp "$OUT/dex/classes.dex" .

echo "[4/5] zip"
python3 - "$OUT" <<'PYX'
import zipfile, os, sys
o = sys.argv[1]
with zipfile.ZipFile(o + '/base.apk') as src, zipfile.ZipFile(o + '/unsigned.apk', 'w') as z:
    for it in src.infolist():
        # resources.arsc 必须 STORED，否则 Android 11+ 装不上
        if it.filename == 'resources.arsc':
            zi = zipfile.ZipInfo('resources.arsc')
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, src.read(it.filename))
        else:
            z.writestr(it, src.read(it.filename), zipfile.ZIP_DEFLATED)
    z.write(o + '/classes.dex', 'classes.dex')
    for r, _, fs in os.walk(o + '/META-INF'):
        for f in fs:
            p = os.path.join(r, f)
            z.write(p, os.path.relpath(p, o))
print('packed', os.path.getsize(o + '/unsigned.apk'))
PYX

echo "[5/5] align + sign"
$ZIPALIGN -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
$JAVA -jar "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:$KS_PASS --ks-key-alias "$KS_ALIAS" \
    --key-pass pass:$KS_KEYPASS --min-sdk-version 26 --out "$OUT/signed.apk" "$OUT/aligned.apk"
$JAVA -jar "$APKSIGNER" verify --min-sdk-version 26 "$OUT/signed.apk" && echo "OK: $OUT/signed.apk"
