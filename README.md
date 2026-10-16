# Kiko# AqarExit Tool (Android WebView)

Opens aqarexit.com and adds a "🔍 الأداة" button that loads every unit in a region group
(splitting searches to get past the site's 192-results cap), then filters and sorts by
total contract price, price per m² and area, shows the 3 cheapest price-per-m² units of the
same compound, opens a compound tab sorted by price per m², and exports CSV (opens in Excel).

## Build
- GitHub: push this folder to a repo → Actions builds `AqarExitTool.apk` and attaches it to a Release.
- Android Studio: File › Open this folder → Build › Build APK(s).

The tool logic is in `app/src/main/assets/ax-tool.js`.
