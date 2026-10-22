# HeadLink

Head-unit app (TS18) that runs phone apps on a **separate virtual display** sized to the head unit — not mirroring.
Built on scrcpy-server 4.1 (downloaded at build time). Nothing is installed on the phone.

## Phone (once)
1. Developer options → **USB debugging** on
2. Developer options → **Disable permission monitoring** on (ColorOS — touch may not work without it)

## Head unit
1. Install `HeadLink.apk` from Releases.
2. Plug the phone in by USB and open HeadLink.
3. Allow USB access; on the phone allow USB debugging with **Always allow**.
4. Pick an app from **Apps**.

## Side bar
- **Back**: Back key for the running app
- **Apps**: phone app list (recently used have a yellow ring)
- **Connect**: reconnect
- **DPI**: UI scale (160 → 320)
- **Log**: server log for troubleshooting (opens automatically on errors)

## Known issues
- If ZLink grabs the USB connection: force-stop ZLink, then tap Connect.
- Audio is not forwarded yet: keep phone audio on the head unit's Bluetooth.
