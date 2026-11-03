# Addon backend: origin and license

The files in this package (`app.gamenative.wow.addons` and `.provider`) and their tests are taken from
WoWForeverywhere by YoruDev-Ryland, https://github.com/YoruDev-Ryland/WoWForeverywhere (GPL-3.0), on 2026-10-23.
Changes here: the CurseForge and Wago providers are not included; addons found on disk take a source only from
a WoWInterface id; the GitHub provider has a built-in fallback list when the shared catalog cannot be fetched.
The UI (`ui/screen/wow/WowAddonsPage.kt`) is this app's own.
