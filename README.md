# Beather ☀️🌧️

Android home-screen weather widget built for Croatia.

## Where the forecast comes from

| What | Source |
|---|---|
| Forecast (hourly + 7 days) | **ECMWF IFS HRES 9 km** via Open-Meteo (free, no API key). ECMWF is the global model DHMZ blends into its own city forecasts. |
| "Right now" temperature & humidity | **DHMZ live stations** (vrijeme.hr): Beather uses the nearest station if it's within 15 km and the reading is under 3 h old. |
| Place search | Open-Meteo geocoding, limited to Croatia by default |

You can switch models in the app menu: ECMWF IFS 9 km (default), Open-Meteo Best match, DWD ICON, ECMWF AIFS (AI). You can also turn the DHMZ stations off.

## Features
- Resizable widget with 3 layouts: small (2×2), medium (4×2: now + 5 days), large (4×3+: now + 12 h + 5 days)
- Each widget has its own location. You pick it when placing the widget; long-press → reconfigure to change it.
- Add places by search (towns, islands, villages) or with **My location** (GPS)
- Shows bura or jugo when there's strong NE/SE wind on the coast
- Day/night colours, Croatian + English UI (follows the phone language)
- Refreshes every 30 min via WorkManager; tap ⟳ on the widget to refresh now

## Build

**Android Studio:** open the folder, let Gradle sync, then Run ▶ (or *Build → Build APK*).

**No Android Studio:** push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds the APK. Download it from the run's
*Artifacts*, under **Beather-apk**.

Requires Android 8.0+ (API 26). Release APKs are signed with a permanent key from the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` repository secrets, so every build installs as an update. The app checks GitHub Releases for updates itself.
