#!/bin/sh
#
# Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
#
# e2e.sh — Decoder plays a camera through its camex tunnel (#272), on the Android
# emulator:
#
#   1. builds the debug APK for x86_64 (Docker image with JDK 17, Android SDK 36, NDK r27c)
#   2. builds camex from bundles/camex (the release whose library the app uses)
#   3. boots an x86_64 Android emulator in Docker; the hub (camex server) and a
#      Linux client (camex client "camera") run in containers of their own on the
#      same Docker network, and an RTSP camera (mediamtx with a test picture) runs in the Linux
#      client's network namespace: the emulator reaches the camera only through the tunnel
#   4. installs the app, allows its VPN (appops), writes the Network profile and slot 1 =
#      the camera's tunnel address into Decoder's settings
#   5. checks: with Network off the camera has no viewer and the edge does not answer; with
#      Network on tun0 gets an address from the hub, ping through the tunnel, the camera is
#      read from the box's tunnel address, Decoder gets the 1280x720 picture, the debug
#      page through the tunnel asks for the password of Network, no crash
#
# Usage: tests/e2e.sh [API]        (default 25; x86_64 "default" images: 21, 23, 25, 34)
#
# Environment:
#   ANDROID_IMAGE   image with the SDK and NDK          (default compot/android:2026.10)
#   EMULATOR_IMAGE  image with the emulator libraries   (default compot/android-emulator:2026.11)
#   CROSS_IMAGE     image with autotools and gcc; also runs the hub and the Linux edge
#                                                       (default compot/cross:2026.11)
#   CAMERA_IMAGE    RTSP server with ffmpeg             (default bluenviron/mediamtx:1.21.1-ffmpeg)
#   EMULATOR_DIR    emulator, platform-tools and system images, mounted at /emu
#                   (default ~/.cache/compot/toolchains/android-emulator)
#   GRADLE_CACHE    Gradle cache                        (default ~/.gradle)
#   KVM_GROUP       group id of /dev/kvm on the Docker host (default 992)
#   SHOTS           1: slot 1 over TCP, a lighter camera stream and screenshots in tests/out/shots
#                   (the picture through the tunnel, the menu, the Network dialog); the emulator's
#                   software decoder still drops some frames: README pictures come from a TV box
#
# Output in tests/out/ (ignored by git). Exit code 0 when all checks pass.
#

set -eu

API=${1:-25}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/tests/out
ANDROID_IMAGE=${ANDROID_IMAGE:-compot/android:2026.10}
EMULATOR_IMAGE=${EMULATOR_IMAGE:-compot/android-emulator:2026.11}
CROSS_IMAGE=${CROSS_IMAGE:-compot/cross:2026.11}
CAMERA_IMAGE=${CAMERA_IMAGE:-bluenviron/mediamtx:1.21.1-ffmpeg}
EMULATOR_DIR=${EMULATOR_DIR:-$HOME/.cache/compot/toolchains/android-emulator}
GRADLE_CACHE=${GRADLE_CACHE:-$HOME/.gradle}
KVM_GROUP=${KVM_GROUP:-992}
SHOTS=${SHOTS:-}
TUNNEL=camex
RESTART_MARK="Tunnel initialized"   # camex's log line of each new run (bundles/camex/src/camex.c)
NAME=decoder-e2e-$$
PEER=$NAME-edge
HUB=$NAME-hub
CAM=$NAME-camera
NET=$NAME-net
PKG=com.openipc.decoder
ACTIVITY=$PKG/com.openipc.decoder.Decoder
KEY=e2e-secret
UID_GID="$(id -u):$(id -g)"

pass=0
fail=0
ok() { pass=$((pass + 1)); echo "PASS: $*"; }
bad() { fail=$((fail + 1)); echo "FAIL: $*"; }

cleanup() {
    docker rm -f "$NAME" "$CAM" "$PEER" "$HUB" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

mkdir -p "$OUT/w"
rm -rf "$OUT/w/.emu"

echo "== debug APK (x86_64, $TUNNEL)"
docker run --rm --pull never -u "$UID_GID" -v "$ROOT:/src" -v "$GRADLE_CACHE:/cache/gradle" \
    -w /src/android "$ANDROID_IMAGE" ./gradlew --no-daemon -q -PdecoderAbis=x86_64 assembleDebug \
    > "$OUT/gradle.log" 2>&1 || { tail -30 "$OUT/gradle.log"; exit 1; }
cp "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" "$OUT/w/decoder.apk"

echo "== camex"
docker run --rm --pull never -u "$UID_GID" -v "$ROOT:/src" -w /src "$CROSS_IMAGE" sh -c '
    rm -rf tests/out/camex && cp -a bundles/camex tests/out/camex &&
    cd tests/out/camex && make -s LDFLAGS=-static' \
    > "$OUT/camex.log" 2>&1 || { tail -30 "$OUT/camex.log"; exit 1; }
cp "$OUT/camex/camex" "$OUT/w/"
KEYS=$("$OUT/w/camex" --genkey)
printf '%s\n' "$KEYS" | sed -n 's/^server_key=/server_key=/p' > "$OUT/w/server.key"
CAMEX_PUBKEY=$(printf '%s\n' "$KEYS" | sed -n 's/^public_key=//p')
printf '[server]\n' > "$OUT/w/camex.conf"

# The AVD: x86_64 "default" image of the API, made by hand (avdmanager does not see the images)
cat > "$OUT/w/avd.sh" <<EOS
#!/bin/sh
export ANDROID_SDK_ROOT=/emu ANDROID_HOME=/emu ANDROID_AVD_HOME=/w/.emu/avd HOME=/w/.emu/home
A=\$ANDROID_AVD_HOME/e2e.avd
mkdir -p \$A \$HOME
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-$API\n' \$A > \$ANDROID_AVD_HOME/e2e.ini
printf 'abi.type=x86_64\nhw.cpu.arch=x86_64\nimage.sysdir.1=system-images/android-$API/default/x86_64/\ntag.id=default\nhw.ramSize=2048\ndisk.dataPartition.size=2G\nhw.lcd.width=1280\nhw.lcd.height=720\nhw.lcd.density=213\nhw.keyboard=no\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\n' > \$A/config.ini
/emu/emulator/emulator -avd e2e -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on > /w/emulator.log 2>&1 &
/emu/platform-tools/adb wait-for-device
until [ "\$(/emu/platform-tools/adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
echo BOOTED
sleep 100000
EOS

echo "== emulator (API $API)"
# a network of its own: the default bridge overlaps some lab networks
docker network create "$NET" >/dev/null
docker run -d --name "$NAME" --network "$NET" --pull never --device /dev/kvm --group-add "$KVM_GROUP" \
    --cap-add NET_ADMIN --device /dev/net/tun -u "$UID_GID" \
    -v "$EMULATOR_DIR:/emu:ro" -v "$OUT/w:/w" "$EMULATOR_IMAGE" sh /w/avd.sh >/dev/null
i=0
until docker logs "$NAME" 2>&1 | grep -q BOOTED; do
    i=$((i + 1)); [ $i -lt 120 ] || { echo "the emulator did not boot"; exit 1; }
    sleep 5
done

# the hub and the Linux edge in containers of their own: the edge's address (and the camera
# behind it) is not reachable from the emulator other than through the tunnel
rm -f "$OUT/w/edge.log" "$OUT/w/server.log"
# the camex server (no start hold of its address pool: CAMEX_POOL_HOLD=0) and the camera's
# client; clients reach each other through the server (client_isolation is off)
HUB_PORT=5800
docker run -d --name "$HUB" --network "$NET" --pull never --cap-add NET_ADMIN --device /dev/net/tun \
    -e CAMEX_POOL_HOLD=0 -v "$OUT/w:/w" "$CROSS_IMAGE" sh -c "/w/camex --mode server --port 5800 \
    --local-cidr 10.200.0.1/24 --server-key-file /w/server.key --psk $KEY --config /w/camex.conf \
    > /w/server.log 2>&1" >/dev/null
HUB_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$HUB")
sleep 1
docker run -d --name "$PEER" --network "$NET" --pull never --cap-add NET_ADMIN --device /dev/net/tun \
    -v "$OUT/w:/w" "$CROSS_IMAGE" sh -c "/w/camex --mode client --auto --name camera --server-host $HUB \
    --port 5800 --server-pubkey $CAMEX_PUBKEY --psk $KEY > /w/edge.log 2>&1" >/dev/null
i=0
# the client's tunnel address: the LOCAL /32 of 10.200. in its routing table (no ip tool there)
until LINUX_IP=$(docker exec "$PEER" cat /proc/net/fib_trie 2>/dev/null |
        awk '/\|-- 10\.200\./ { a = $2 } /\/32 host LOCAL/ && a { print a; exit }') && [ -n "$LINUX_IP" ]; do
    i=$((i + 1)); [ $i -lt 30 ] || { echo "the camex client got no address"; exit 1; }
    sleep 1
done
echo "hub ($TUNNEL): $HUB_IP:$HUB_PORT, Linux client: $LINUX_IP"

# the camera: an RTSP server in the Linux edge's network namespace, a 1280x720 test picture
# (screenshots: a lighter stream, the emulator's software decoder keeps up with it)
RATE=25
X264='-preset ultrafast'
[ -z "$SHOTS" ] || { RATE=15; X264='-preset veryfast -b:v 1500k -maxrate 1500k -bufsize 3000k'; }
docker run -d --name "$CAM" --pull never --net "container:$PEER" -e MTX_RTSPTRANSPORTS=tcp,udp \
    -e "MTX_PATHS_TEST_RUNONINIT=ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=$RATE -c:v libx264 $X264 -tune zerolatency -g $RATE -pix_fmt yuv420p -f rtsp rtsp://localhost:8554/test" \
    -e MTX_PATHS_TEST_RUNONINITRESTART=yes "$CAMERA_IMAGE" >/dev/null
i=0
until docker logs "$CAM" 2>&1 | grep -q 'stream is available'; do
    i=$((i + 1)); [ $i -lt 30 ] || { echo "the camera did not start"; docker logs "$CAM" 2>&1 | tail -5; exit 1; }
    sleep 1
done
CAMERA="rtsp://$LINUX_IP:8554/test"
echo "camera: $CAMERA"
readers() { docker logs "$CAM" 2>&1 | grep -c "is reading from path" || true; }

ADB="docker exec $NAME /emu/platform-tools/adb"
$ADB install -r /w/decoder.apk >/dev/null
# the VPN without the consent dialog, the notification permission on Android 13+
# (Android 5 knows the op only by its number, 47 = OP_ACTIVATE_VPN)
$ADB shell appops set $PKG ACTIVATE_VPN allow 2>&1 | grep -q 'Unknown operation' &&
    $ADB shell appops set $PKG 47 allow
[ "$API" -lt 33 ] || $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS

# the password of the debug page (Network → Web page password), plain: read as it is
WEB_PASSWORD=e2e-pass
# the tunnel's values as the Network screen keeps them (tunnel_<id>_<field>)
TUNNEL_SETTINGS="<string name=\"tunnel_camex_server\">$HUB_IP:5800</string>\
<string name=\"tunnel_camex_name\">decoder</string>\
<string name=\"tunnel_camex_transport\">UDP</string>\
<string name=\"tunnel_camex_pubkey\">$CAMEX_PUBKEY</string>\
<string name=\"tunnel_camex_psk\">$KEY</string>\
<string name=\"web_password\">$WEB_PASSWORD</string>"
# write_settings ON: Decoder's settings with slot 1 = the camera and the camex tunnel's values
# (tunnel_camex_<field>), on or off (debug builds allow run-as; a plain key and URL are read as
# they are)
write_settings() {
    printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>\
<string name=\"host_0\">$CAMERA</string>\
${SHOTS:+<string name=\"transport_0\">TCP</string>}\
$TUNNEL_SETTINGS\
<boolean name=\"tunnel_on\" value=\"$1\" /></map>" > "$OUT/w/settings.xml"
    # (old adbd does not pass stdin to the shell: push the file, then copy it as the app)
    $ADB push /w/settings.xml /data/local/tmp/settings.xml >/dev/null
    $ADB shell chmod 644 /data/local/tmp/settings.xml
    $ADB shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat /data/local/tmp/settings.xml > shared_prefs/settings.xml'"
    $ADB shell rm -f /data/local/tmp/settings.xml
}

# the control: Network off, the camera's tunnel address is out of reach
write_settings false
$ADB shell am start -n $ACTIVITY >/dev/null
sleep 12
if $ADB shell ping -c 2 -W 2 "$LINUX_IP" 2>&1 | grep -q ' [1-9][0-9]* received'; then
    bad "$LINUX_IP answers without the VPN: the test network leaks"
else
    ok "no answer from $LINUX_IP with Network off"
fi
if [ "$(readers)" -eq 0 ]; then ok "the camera has no viewer with Network off"; else bad "the camera was read with Network off"; fi

# Network on: the app connects at its start
$ADB shell am force-stop $PKG
write_settings true
$ADB logcat -c
$ADB shell am start -n $ACTIVITY >/dev/null
# Android 5 has no appops for the VPN: confirm the system dialog the way a user would
sleep 4
if $ADB shell dumpsys activity activities | grep -q 'vpndialogs/.ConfirmDialog'; then
    B=""
    for _ in 1 2 3; do
        $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || true
        B=$($ADB shell cat /data/local/tmp/ui.xml 2>/dev/null | grep -o 'text="OK"[^>]*bounds="[^"]*"' |
            sed -n 's/.*\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p')
        [ -z "$B" ] || break
        sleep 1
    done
    if [ -n "$B" ]; then
        # shellcheck disable=SC2086 # four numbers
        set -- $B
        $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
    else
        $ADB shell input keyevent 22 && $ADB shell input keyevent 66
    fi
fi

i=0
until TUN=$($ADB shell 'ip addr show tun0 2>/dev/null' | sed -n 's/.*inet \([0-9.]*\).*/\1/p') && [ -n "$TUN" ]; do
    i=$((i + 1)); [ $i -lt 90 ] || break   # Android 5 on the emulator takes its time
    sleep 1
done
if [ -n "$TUN" ]; then ok "tun0 has $TUN from the hub"; else
    bad "no tun0 address (tests/out/: ip.txt, screen.png, logcat.txt)"
    $ADB shell ip addr > "$OUT/ip.txt" 2>&1 || true
    $ADB exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
    $ADB logcat -d > "$OUT/logcat.txt" 2>&1 || true
fi

# a few rounds: the edges find each other through the hub first (slow on Android 5)
pinged=""
for _ in 1 2 3 4 5; do
    if $ADB shell ping -c 3 -W 2 "$LINUX_IP" | grep -q ' [123] received'; then pinged=1; break; fi
done
if [ -n "$pinged" ]; then ok "ping through the tunnel to $LINUX_IP"; else bad "no ping to $LINUX_IP"; fi

# the tunnel is IPv4 only: the VPN must not block the app's IPv6 (allowFamily(AF_INET6)); a
# blocked family shows as a ::/0 route of tun0 in the VPN's link properties
$ADB shell dumpsys connectivity > "$OUT/connectivity.txt" 2>&1 || true
if grep 'InterfaceName: tun0' "$OUT/connectivity.txt" | grep -q '::/0'; then
    bad "the VPN blocks IPv6 (::/0 on tun0, tests/out/connectivity.txt)"
else ok "the VPN leaves IPv6 alone (no ::/0 on tun0)"; fi

# Decoder retries the camera on its own: wait for a viewer from the tunnel address
i=0
until [ -n "${TUN:-}" ] && docker logs "$CAM" 2>&1 | grep -q "created by $TUN:"; do
    i=$((i + 1)); [ $i -lt 60 ] || break
    sleep 1
done
if [ -n "${TUN:-}" ] && docker logs "$CAM" 2>&1 | grep -q "created by $TUN:" && [ "$(readers)" -ge 1 ]; then
    ok "the camera is read from the tunnel address $TUN"
else
    bad "the camera has no viewer from the tunnel (tests/out/camera.log)"
    docker logs "$CAM" > "$OUT/camera.log" 2>&1 || true
fi
# Auto plays a camera behind the tunnel over TCP (#272: the tunnel's MTU is below its RTP packets)
if [ -z "$SHOTS" ]; then
    if docker logs "$CAM" 2>&1 | grep "created by $TUN:" >/dev/null && docker logs "$CAM" 2>&1 | grep -q "is reading from path 'test', with TCP"; then
        ok "Auto plays the tunnel camera over TCP"
    else
        bad "the tunnel camera is not read over TCP (tests/out/camera.log)"
        docker logs "$CAM" > "$OUT/camera.log" 2>&1 || true
    fi
fi
i=0
until $ADB logcat -d 2>/dev/null | grep -q 'Resolution update: 1280x720'; do
    i=$((i + 1)); [ $i -lt 30 ] || break
    sleep 1
done
if $ADB logcat -d 2>/dev/null | grep -q 'Resolution update: 1280x720'; then
    ok "Decoder gets the 1280x720 picture"
else
    bad "no picture in Decoder (tests/out/logcat.txt, screen.png)"
    $ADB logcat -d > "$OUT/logcat.txt" 2>&1 || true
    $ADB exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
fi
$ADB exec-out screencap -p > "$OUT/decoder.png" 2>/dev/null || true

# the debug page through the tunnel, from the Linux client: the password set in Network is asked
code() { docker exec "$PEER" curl -s -o /dev/null -m 10 -w '%{http_code}' "$@" "http://$TUN:8085/stats.json"; }
c0=$(code) c1=$(code -u "x:wrong") c2=$(code -u "anyone:$WEB_PASSWORD")
if [ "$c0" = 401 ] && [ "$c1" = 401 ] && [ "$c2" = 200 ]; then
    ok "the debug page through the tunnel asks for the password (401, 401, 200)"
else
    bad "debug page through the tunnel: no password $c0, wrong $c1, right $c2 (want 401 401 200)"
fi

if [ -n "$SHOTS" ]; then
    # the README's screenshots: the picture, the menu over it (OK), the Network dialog
    mkdir -p "$OUT/shots"
    sleep 8
    $ADB exec-out screencap -p > "$OUT/shots/tunnel_video.png"
    $ADB shell input keyevent 23
    sleep 2
    $ADB exec-out screencap -p > "$OUT/shots/tunnel_menu.png"
    # the first Down puts the focus on the active slot, three more reach Network
    for _ in 1 2 3 4; do $ADB shell input keyevent 20; sleep 0.5; done
    $ADB shell input keyevent 23
    sleep 3
    $ADB exec-out screencap -p > "$OUT/shots/tunnel_network.png"
    # down to the On switch: the dialog scrolls to the cipher, TCP, multicast and On
    for _ in 1 2 3 4 5 6 7; do $ADB shell input keyevent 20; sleep 0.4; done
    sleep 1
    $ADB exec-out screencap -p > "$OUT/shots/tunnel_network_options.png"
    $ADB shell input keyevent 4
    docker logs "$CAM" > "$OUT/shots/camera.log" 2>&1 || true
    $ADB logcat -d > "$OUT/shots/logcat.txt" 2>&1 || true
    echo "screenshots in tests/out/shots"
fi

# the camera's URL dialog (Settings): a phone (touch) gets the keyboard at once, a remote
# (D-pad) only on OK, so a TV box's keyboard does not take the remote (the AVD has no hardware
# keyboard, so the soft one shows)
shown() { $ADB shell dumpsys input_method | grep -o 'mInputShown=[a-z]*' | head -1; }
bounds() {
    $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || true
    $ADB shell cat /data/local/tmp/ui.xml 2>/dev/null | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" |
        sed -n 's/.*\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p' | head -1
}
tap_text() {
    B=$(bounds "$1")
    [ -n "$B" ] || return 1
    # shellcheck disable=SC2086 # four numbers
    set -- $B
    $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}
# Back hides the keyboard, then Cancel closes the dialog
close_dialog() {
    [ "$(shown)" != mInputShown=true ] || { $ADB shell input keyevent 4; sleep 1; }
    tap_text Cancel || true
    sleep 2
}
if [ -z "$SHOTS" ]; then
    $ADB shell input tap 640 360
    sleep 2
    tap_text Settings || true
    sleep 3
    $ADB exec-out screencap -p > "$OUT/kb-touch.png" 2>/dev/null || true
    if [ "$(shown)" = mInputShown=true ]; then ok "touch: the URL dialog opens with the keyboard"; else bad "touch: no keyboard in the URL dialog (tests/out/kb-touch.png)"; fi
    close_dialog
    # the remote: OK opens the menu, Down to Settings (after a touch the first key only puts
    # the focus on the active slot), OK opens the dialog
    $ADB shell input keyevent 23
    sleep 2
    for _ in 1 2 3; do
        $ADB shell input keyevent 20
        sleep 1
        $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || true
        $ADB shell cat /data/local/tmp/ui.xml 2>/dev/null | grep -o '<node[^>]*focused="true"[^>]*>' | grep -q 'text="Settings"' && break
    done
    $ADB shell input keyevent 23
    sleep 3
    $ADB exec-out screencap -p > "$OUT/kb-remote.png" 2>/dev/null || true
    if [ "$(shown)" = mInputShown=false ] && bounds Cancel | grep -q '[0-9]'; then
        ok "remote: the URL dialog opens without the keyboard"
    else
        bad "remote: the URL dialog took the keyboard or did not open (tests/out/kb-remote.png)"
    fi
    $ADB shell input keyevent 23
    sleep 2
    if [ "$(shown)" = mInputShown=true ]; then ok "remote: OK in the field opens the keyboard"; else bad "remote: OK opened no keyboard (tests/out/logcat-kb.txt)"; $ADB logcat -d > "$OUT/logcat-kb.txt" 2>&1 || true; fi
    close_dialog
fi

# the tunnel restarts in the same process: Network → Save stops it and starts it again with the
# saved values (a second run of the tunnel's native code in one process); tun0 comes back and the
# camera's address answers again
if [ -z "$SHOTS" ]; then
    $ADB logcat -c 2>/dev/null || true
    $ADB shell input tap 640 360
    sleep 2
    tap_text Network || true
    sleep 3
    $ADB exec-out screencap -p > "$OUT/restart-network.png" 2>/dev/null || true
    $ADB shell cat /data/local/tmp/ui.xml > "$OUT/restart-ui.xml" 2>/dev/null || true
    # a touch on Android 5 opens the keyboard over the dialog's buttons: Back hides it
    [ "$(shown)" != mInputShown=true ] || { $ADB shell input keyevent 4; sleep 1; }
    tap_text Save || true
    sleep 5
    $ADB exec-out screencap -p > "$OUT/restart-saved.png" 2>/dev/null || true
    again=""
    for _ in $(seq 1 30); do
        if $ADB shell 'ip addr show tun0 2>/dev/null' | grep -q 'inet ' &&
                $ADB shell ping -c 2 -W 2 "$LINUX_IP" | grep -q ' [12] received'; then again=1; break; fi
        sleep 2
    done
    $ADB logcat -d > "$OUT/logcat-restart.txt" 2>&1 || true
    if [ -n "$again" ] && grep -q "$RESTART_MARK" "$OUT/logcat-restart.txt"; then
        ok "the tunnel restarts in the same process (Network → Save)"
    else
        bad "no tunnel after Network → Save (tests/out/logcat-restart.txt)"
    fi
fi

$ADB logcat -d -b crash > "$OUT/crash.log" 2>&1 || true
if grep -q "$PKG" "$OUT/crash.log"; then bad "crash in logcat (tests/out/crash.log)"; else ok "no crash"; fi

echo "== $pass passed, $fail failed"
[ "$fail" -eq 0 ]
