#!/usr/bin/env bash
# Compile FFmpeg + x264 pour Android et depose l'executable dans
# app/app/src/main/jniLibs/<abi>/libffmpeg.so.
#
# Pourquoi un executable nomme "lib*.so" : depuis Android 10, une app ne peut
# plus executer un binaire place dans ses dossiers inscriptibles (W^X). Le
# dossier des bibliotheques natives (nativeLibraryDir) reste executable, et le
# packager n'y extrait que des fichiers "lib*.so". L'app lance donc ce fichier
# avec ProcessBuilder, comme un ffmpeg de bureau, sans JNI ni dependance tierce.
#
# Usage :
#   scripts/build-ffmpeg-android.sh            # arm64-v8a (defaut)
#   ABIS="arm64-v8a x86_64" scripts/build-ffmpeg-android.sh
#   HOST=1 scripts/build-ffmpeg-android.sh     # meme configuration, pour Linux :
#                                              # sert a tester les commandes de l'app
#
# Variables : ANDROID_NDK_HOME (NDK r27+), BUILD_DIR, API (defaut 24).
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
X264_REF="${X264_REF:-stable}"
API="${API:-24}"
ABIS="${ABIS:-arm64-v8a}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/ffmpeg}"
OUT_DIR="$ROOT/app/app/src/main/jniLibs"
JOBS="$(nproc 2>/dev/null || echo 4)"

mkdir -p "$BUILD_DIR/src"
cd "$BUILD_DIR/src"

if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
  curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" | tar -xJ
fi
# Bogue FFmpeg 7.1 : avec --disable-everything, le decodeur h264 seul reference
# aom_film_grain.o sans le compiler (corrige en amont apres la 7.1).
grep -q 'CONFIG_H264_SEI).*aom_film_grain' "ffmpeg-$FFMPEG_VERSION/libavcodec/Makefile" ||
  sed -i 's/^\(OBJS-$(CONFIG_H264_SEI) *+= h264_sei.o h2645_sei.o\)$/\1 aom_film_grain.o/' \
    "ffmpeg-$FFMPEG_VERSION/libavcodec/Makefile"

if [ ! -d "x264-$X264_REF" ]; then
  curl -fsSL "https://code.videolan.org/videolan/x264/-/archive/$X264_REF/x264-$X264_REF.tar.bz2" | tar -xj
fi

# Seuls les composants que l'app utilise sont compiles : l'APK reste petit et
# chaque filtre absent se voit tout de suite au test HOST=1.
COMPONENTS=(
  --disable-everything
  --enable-protocol=file,pipe
  --enable-demuxer=image2,png_pipe,jpeg_pipe,webp_pipe,wav,concat,mov,mp3,aac
  --enable-muxer=mp4,mov,wav,null,image2
  --enable-decoder=png,mjpeg,webp,pcm_s16le,pcm_s24le,pcm_s32le,pcm_u8,pcm_f32le,pcm_f64le,wrapped_avframe,h264,aac,mp3
  --enable-encoder=libx264,aac,pcm_s16le,mjpeg,png
  --enable-parser=h264,aac,png,mjpeg,webp
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb
  --enable-indev=lavfi
  --enable-filter=scale,crop,pad,zoompan,format,fps,setsar,setpts,trim,null,copy,split,overlay,fade,vignette,eq,noise
  --enable-filter=anull,aformat,aresample,anullsrc,apad,atrim,asetpts,afade,amix,volume,sidechaincompress,alimiter,asplit,anoisesrc,lowpass,highpass,sine,aevalsrc,concat,pan,amerge
)

COMMON=(
  --enable-gpl --enable-libx264 --enable-zlib
  --enable-static --disable-shared --enable-pic
  --disable-autodetect --disable-doc --disable-debug --disable-network
  --disable-ffplay --disable-ffprobe
  "${COMPONENTS[@]}"
)

build_host() {
  local prefix="$BUILD_DIR/host"
  ( cd "x264-$X264_REF" && make distclean >/dev/null 2>&1 || true
    ./configure --prefix="$prefix" --enable-static --enable-pic --disable-cli --disable-opencl --disable-asm
    make -j"$JOBS" && make install )
  ( cd "ffmpeg-$FFMPEG_VERSION" && make distclean >/dev/null 2>&1 || true
    PKG_CONFIG_PATH="$prefix/lib/pkgconfig" ./configure --prefix="$prefix" \
      --extra-cflags="-I$prefix/include" --extra-ldflags="-L$prefix/lib" \
      --pkg-config-flags=--static --disable-x86asm --disable-inline-asm "${COMMON[@]}"
    make -j"$JOBS" && make install )
  echo "FFmpeg hote : $prefix/bin/ffmpeg"
}

build_android() {
  local abi="$1"
  local ndk="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME doit pointer sur un NDK r27+}"
  # Hote Windows (MSYS2) ou macOS : le NDK y range sa toolchain ailleurs.
  local host_tag=linux-x86_64
  case "$(uname -s)" in
    MINGW*|MSYS*) host_tag=windows-x86_64 ;;
    Darwin) host_tag=darwin-x86_64 ;;
  esac
  local toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag"
  local triple arch cpu
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; arch=aarch64; cpu=armv8-a ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; arch=arm; cpu=armv7-a ;;
    x86_64) triple=x86_64-linux-android; arch=x86_64; cpu=x86-64 ;;
    *) echo "ABI non geree : $abi" >&2; exit 1 ;;
  esac
  local cc="$toolchain/bin/${triple}${API}-clang"
  local prefix="$BUILD_DIR/$abi"
  # Pages de 16 Ko : obligatoire sur les appareils Android 15+ qui les utilisent,
  # sans effet sur les autres.
  local ldflags="-Wl,-z,max-page-size=16384"

  ( cd "x264-$X264_REF" && make distclean >/dev/null 2>&1 || true
    local host="$triple"; [ "$abi" = armeabi-v7a ] && host=arm-linux-androideabi
    local asm=(); [ "$abi" = x86_64 ] && asm=(--disable-asm)
    CC="$cc" ./configure --prefix="$prefix" --host="$host" \
      --cross-prefix="$toolchain/bin/llvm-" --sysroot="$toolchain/sysroot" \
      --enable-static --enable-pic --disable-cli --disable-opencl \
      --extra-ldflags="$ldflags" "${asm[@]}"
    make -j"$JOBS" && make install )

  ( cd "ffmpeg-$FFMPEG_VERSION" && make distclean >/dev/null 2>&1 || true
    # x86 : l'asm en ligne de swscale corrompt la sortie YUV (bandes colorees)
    # dans cette configuration ; arm64 garde son NEON.
    local asm=(); [ "$abi" = x86_64 ] && asm=(--disable-x86asm --disable-inline-asm)
    PKG_CONFIG_PATH="$prefix/lib/pkgconfig" PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" \
    ./configure --prefix="$prefix" --target-os=android --arch="$arch" --cpu="$cpu" \
      --enable-cross-compile --cc="$cc" --cxx="${cc}++" \
      --ar="$toolchain/bin/llvm-ar" --nm="$toolchain/bin/llvm-nm" \
      --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" \
      --sysroot="$toolchain/sysroot" \
      --extra-cflags="-I$prefix/include -O2" --extra-ldflags="-L$prefix/lib $ldflags" \
      --pkg-config=pkg-config --pkg-config-flags=--static "${asm[@]}" "${COMMON[@]}"
    make -j"$JOBS" && make install )

  mkdir -p "$OUT_DIR/$abi"
  "$toolchain/bin/llvm-strip" -o "$OUT_DIR/$abi/libffmpeg.so" "$prefix/bin/ffmpeg"
  ls -lh "$OUT_DIR/$abi/libffmpeg.so"
}

if [ "${HOST:-0}" = 1 ]; then
  build_host
else
  for abi in $ABIS; do build_android "$abi"; done
fi
