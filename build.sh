#!/usr/bin/env bash
# Snow Leopard Decision Core - zero-dependency build (javac + jar, no Gradle)
#
#   ./build.sh                # 核心 jar + 源码 jar
#   ./build.sh check [name]   # 编译并运行检查器（name 默认 smoke，all 跑全部）
#   ./build.sh publish [dir]  # 产出 maven 布局（dist/m2），给了 dir 就再复制一份
#
# 全量源文件清单编译（javac @argfile）：产品树每个 .java 都进产物，不搞编译锚点。
# argfile 路径相对 $ROOT（先 cd 再 javac），绕开非 ASCII 绝对路径被平台编码读取的坑。
# --release 21 是硬要求：不写它，新 JDK 会静默产出更高 class 版本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src/main/java"
SRC_CHECK="$ROOT/src/check/java"
OUT="$ROOT/build/classes"
TEST="$ROOT/build/test"
DIST="$ROOT/dist"
VERSION="$(cat "$ROOT/VERSION" 2>/dev/null || echo 0.0.0)"

resolve_bin() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
        echo "$JAVA_HOME/bin"; return
    fi
    local d
    for d in "/c/Program Files/Java"/*/bin "/c/Program Files/Eclipse Adoptium"/*/bin; do
        if [ -x "$d/jar.exe" ] || [ -x "$d/jar" ]; then
            echo "$d"; return
        fi
    done
    echo ""
}

BIN="$(resolve_bin)"
if [ -z "$BIN" ]; then
    echo "[build] jar not found. Set JAVA_HOME to a full JDK."
    exit 1
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
    *)                    SEP=":" ;;
esac

winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

rm -rf "$OUT"
mkdir -p "$OUT" "$DIST" "$ROOT/build"

# --- 核心（全量清单） ---
( cd "$ROOT" && find "src/main/java" -name '*.java' | sort > "$ROOT/build/sources.txt" )
echo "[build] javac 源文件: $(wc -l < "$ROOT/build/sources.txt") 个"
( cd "$ROOT" && "$BIN/javac" --release 21 -encoding UTF-8 -d "$OUT" @"$ROOT/build/sources.txt" )

MANIFEST="$ROOT/build/MANIFEST.MF"
cat > "$MANIFEST" <<EOF
Manifest-Version: 1.0
Implementation-Title: Snow Leopard Decision Core
Implementation-Version: $VERSION
Automatic-Module-Name: snowleopard.core
EOF

"$BIN/jar" --create --file "$DIST/snowleopard-core.jar" --manifest "$MANIFEST" -C "$OUT" .
echo "[build] core  OK: dist/snowleopard-core.jar ($VERSION)"

"$BIN/jar" --create --file "$DIST/snowleopard-core-src.jar" -C "$SRC" .
echo "[build] src   OK: dist/snowleopard-core-src.jar"

# --- 零 MC 硬约束（构建方式保证，不靠人记） ---
if command -v jdeps >/dev/null 2>&1 || [ -x "$BIN/jdeps" ]; then
    JDEPS="$BIN/jdeps"
    if ! "$JDEPS" --multi-release 21 -verbose:class "$DIST/snowleopard-core.jar" 2>/dev/null \
            | grep -E 'net\.minecraft|net\.neoforged' >/dev/null; then
        echo "[build] jdeps OK: 产物零 MC 引用"
    else
        echo "[build] jdeps FAIL: 产物引用了 net.minecraft / net.neoforged —— core 必须零 MC"
        exit 1
    fi
else
    echo "[build] jdeps 跳过（未找到 jdeps）"
fi

# --- check ---
if [ "${1:-}" = "check" ]; then
    NAME="${2:-smoke}"
    rm -rf "$TEST"; mkdir -p "$TEST"
    if [ "$NAME" = "all" ]; then
        NAMES=$(cd "$SRC_CHECK" && find . -name '*Check.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort)
    else
        CAP="$(echo "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"
        NAMES="snowleopard.core.check.${CAP}Check"
    fi
    rc=0
    for cls in $NAMES; do
        FILE="$SRC_CHECK/${cls//.//}.java"
        if [ ! -f "$FILE" ]; then
            echo "[check] 未找到检查类: $FILE"
            rc=1; continue
        fi
        echo "[check] --- $cls ---"
        "$BIN/javac" -encoding UTF-8 -cp "$(winpath "$DIST/snowleopard-core.jar")" -d "$TEST" "$FILE"
        if java -cp "$(winpath "$DIST/snowleopard-core.jar")$SEP$(winpath "$TEST")" "$cls" "$ROOT"; then
            echo "[check] rc=0"
        else
            echo "[check] rc=1"
            rc=1
        fi
    done
    exit $rc
fi

# --- publish（maven 布局） ---
if [ "${1:-}" = "publish" ]; then
    M2="$DIST/m2"
    D="$M2/snowleopard/core/snowleopard-core/$VERSION"
    rm -rf "$M2"; mkdir -p "$D"
    cp "$DIST/snowleopard-core.jar" "$D/"
    cat > "$D/snowleopard-core-$VERSION.pom" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>snowleopard.core</groupId>
  <artifactId>snowleopard-core</artifactId>
  <version>$VERSION</version>
  <packaging>jar</packaging>
</project>
EOF
    echo "[build] m2   OK: dist/m2（snowleopard.core:snowleopard-core:$VERSION）"
    if [ -n "${2:-}" ]; then
        mkdir -p "$2"
        cp -r "$M2/." "$2/"
        echo "[build] m2   OK: 已复制到 $2"
    fi
    exit 0
fi
