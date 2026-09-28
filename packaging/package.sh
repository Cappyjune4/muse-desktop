#!/usr/bin/env bash
# Builds muse_<version>_amd64.deb: the app with its own Java under /opt/muse, a launcher,
# icons, and "Open with muSE" for music files. apt installs what it needs from Ubuntu:
#   sudo apt install ./build/muse_<version>_amd64.deb
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk}"

# --public: the build for GitHub, without what's in private/ (the ABC logos).
public=false; [ "${1:-}" = "--public" ] && public=true
./gradlew -q -Ppublic=$public clean icon createDistributable
version=$(./gradlew -q printVersion)
app=build/compose/binaries/main/app/muSE
root=build/deb/muse
rm -rf build/deb && mkdir -p "$root/DEBIAN" "$root/opt" "$root/usr/bin" "$root/usr/share/applications"

cp -a "$app" "$root/opt/muse"
ln -s /opt/muse/bin/muSE "$root/usr/bin/muse"
for px in 16 24 32 48 64 128 256 512; do
    install -Dm644 "packaging/icons/muse-$px.png" "$root/usr/share/icons/hicolor/${px}x${px}/apps/muse.png"
done
install -m644 packaging/muse.desktop "$root/usr/share/applications/muse.desktop"

chmod -R u+rwX,go+rX,go-w "$root"
size=$(du -sk "$root" | cut -f1)
cat > "$root/DEBIAN/control" <<CONTROL
Package: muse
Version: $version
Architecture: amd64
Maintainer: Cappyjune4 <149750782+Cappyjune4@users.noreply.github.com>
Installed-Size: $size
Depends: libmpv2 | libmpv1, libsecret-1-0, ffmpeg, zenity, xdg-user-dirs, libc6, libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfreetype6, libfontconfig1, libgl1
Recommends: gnome-keyring
Section: sound
Priority: optional
Homepage: https://github.com/Cappyjune4/muse-desktop
Description: Radio, podcasts, Jellyfin music and your own
 muSE for the desktop: internet radio (with live track names), podcasts,
 your Jellyfin music library, and the music on this computer, played with
 mpv. Works with GNOME's media controls and the keyboard's media keys.
CONTROL

# Yours (with private/) goes into private/ beside the logos; the public one into build/public.
out=private; $public && out=build/public
mkdir -p "$out"
dpkg-deb --root-owner-group --build -Zxz "$root" "$out/muse_${version}_amd64.deb" >/dev/null
echo "$out/muse_${version}_amd64.deb"
