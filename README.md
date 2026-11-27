# Dekh Browser

Personal Android browser (landscape only): bottom address bar, built-in ad/tracker blocking,
HTTPS upgrade, tracking-parameter stripping, no third-party cookies, per-site shields,
desktop-site mode, reader mode, find in page, tab switcher with swipe gestures.

## Get dekhbrowser.apk (no PC needed)
1. New private GitHub repository; upload everything in this folder (keep the `.github` folder) to `main`.
2. Actions tab > "Build dekhbrowser.apk" (also runs on every push, ~3-5 min).
3. Releases > "Dekh Browser (latest)" > download `dekhbrowser.apk`, open it on the phone / head unit.
   It installs over the earlier NabilBrowser build (same package id), keeping settings.

## Car mode
Menu > "Car mode", or open the second icon "Dekh Browser Car". Big controls, 125% text,
screen stays on, tile home page (edit `app/src/main/assets/cartiles.txt`), mic button
("navigate to ...", "play ..."), YouTube video auto-pauses above 8 km/h.

## Dekh Nav (voice navigation)
Tile "Dekh Nav (voice)", the "Navigate to" box, the menu item, or the voice command "navigate to <place>".
OpenStreetMap map + OSRM routing + Android text-to-speech. Speaks "In 400 metres, turn left onto ...",
reroutes if you leave the route, announces arrival. "Open in Google Maps app" hands the trip to the
installed Google Maps app instead. Needs internet, GPS permission, and a text-to-speech voice.

## Orientation
Landscape is set by `android:screenOrientation="sensorLandscape"` on each activity in AndroidManifest.xml.
Delete that line from MainActivity if you want the phone browser to rotate freely.

## Home and Settings (v1.1)
- Bottom bar: Home button (left) goes to your homepage, gear button (right) opens Settings. Settings is also in the menu.
- Settings: homepage, on startup (open homepage / continue where I left off), search engine (or your own URL with %s),
  ads/trackers, HTTPS upgrade, tracking-parameter removal, third-party cookies, clear data on exit, text size,
  desktop sites by default, car mode, pause-video-while-driving, reset.
- Changes apply as soon as you come back to the browser.
