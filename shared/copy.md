# WellRest — product copy

Shared wording for both apps and the store listings. Keep the two apps in sync. Android sources: `AppInfo.kt`, `activity_main.xml`.

## App
- Name: **WellRest**
- Tagline: *Finish your set. Rest starts itself.* (Android) / *Your rest, one tap away.* (iOS, manual start — see ios/HANDOVER.md)

## Main screen
- Header: `WELLREST`
- Ring label: `REST`
- Controls: `−15`, `+15`; presets `30s`, `45s`, `1:00`, `1:30`, `2:00`
- Toggle (Android only): **Auto-start after each set** — *Rings once when the rest is over*
- Toggle: **Stop if I start early** — *Cancel the countdown when the next set starts* (Android only; no set detection on iOS)

## Rest card (Android notification / iOS Live Activity)
- Label: `Rest`
- Subtitle: `Up next: {exercise}` or `Next set`
- Total under the countdown: `{m:ss}` + `REST`
- Button: Skip (icon ⏭)

## Privacy (Settings → About → Privacy)
- Reads only the WHOOP app's workout notification, to detect when a set ends and show the next exercise. *(Android only)*
- No internet permission, no account, no ads, no analytics. Nothing leaves your phone.
- Settings stay on the device.
- Uninstall to remove all data.

## Disclaimer (everywhere WHOOP is named)
> WellRest is an independent app. It is not affiliated with, endorsed or sponsored by WHOOP, Inc. WHOOP is a trademark of WHOOP, Inc.

## Contact
- Support: sylvain.rousseau.apps@gmail.com
- Privacy policy: https://goudurixx.github.io/wellrest/privacy-policy.html
