# WellRest for iOS — handover plan

Everything needed to build the iOS version of WellRest in SwiftUI, alongside the shipped Android app in [`../android`](../android).

**Owner:** iOS engineer · **Product:** Sylvain Rousseau · **Support:** sylvain.rousseau.apps@gmail.com

---

## 1. What WellRest is, and what changes on iOS

On Android, WellRest reads the WHOOP app's workout notification. When the card switches from a set to **"Up Next: …"**, it starts a rest countdown in a WHOOP-styled notification, then rings once.

**iOS can't do that part.** No public API lets a third-party app read other apps' notifications or Live Activities: there's no `NotificationListenerService` equivalent and no permission the user can grant. Apple Health only receives WHOOP workouts once the session ends, and WHOOP's public API reports completed workouts, not sets. ANCS (Bluetooth notification forwarding) only works with external accessories, not apps on the same phone.

**So iOS v1 is a manual-start rest timer, designed to need as little effort as possible:**

| | Android (shipped) | iOS v1 |
|---|---|---|
| Start a rest | Automatic, from WHOOP's notification | **One tap**: Action Button, Control Center / lock-screen control, Siri / Shortcuts, Watch-free in-app button |
| Countdown UI | Custom dark notification card | **Live Activity**: lock screen + Dynamic Island |
| End-of-rest alert | One sound on the alarm stream + double vibration, even on silent | **AlarmKit** alert: breaks through silent mode and Focus |
| Rest length | 15 s – 10 min, ±15 s, presets | Same |
| "Up next" exercise name | Read from WHOOP | Not available (shows "Next set") |
| Stop if next set starts early | Automatic | n/a, user taps Stop |
| Privacy | No internet, no data collected | Same |

If Apple ever opens an API that makes auto-detection possible, it would slot into `RestEngine.start()`. Out of scope for v1.

## 2. Target and stack

- **iOS 26.0+**. AlarmKit is iOS 26-only, and it's the only way to get a reliable alert through silent mode. Supporting iOS 17/18 would need a fallback: a time-sensitive local notification, which respects the silent switch. Product call: iOS 26-only for v1.
- **SwiftUI**, Swift 6 strict concurrency, **Xcode 26**.
- No third-party dependencies. Apple frameworks only: `AlarmKit`, `ActivityKit`, `WidgetKit`, `AppIntents`.
- Bundle ID: **`com.sylvainrousseau.wellrest`** (same as Android, for consistency). App group: `group.com.sylvainrousseau.wellrest`.

## 3. Architecture

```
ios/
├── WellRest.xcodeproj
├── WellRest/                       app target
│   ├── WellRestApp.swift
│   ├── Engine/RestEngine.swift     single entry point: start(seconds:), stop(), state stream
│   ├── Engine/Settings.swift       rest length, presets — @AppStorage in the app group
│   ├── Intents/StartRestIntent.swift   AppIntent: start a rest with the saved length
│   ├── Intents/StopRestIntent.swift
│   ├── UI/MainView.swift           ring, −15 / +15, presets, status
│   ├── UI/SettingsView.swift       About: version, privacy, licences, support, disclaimer
│   ├── UI/Theme.swift              colours/radii from shared/design-tokens.json
│   └── Resources/Assets.xcassets   app icon from shared/brand/icon-1024-ios.png
├── WellRestWidgets/                widget extension
│   ├── RestLiveActivity.swift      ActivityConfiguration(for: AlarmAttributes<RestMetadata>.self)
│   └── StartRestControl.swift      ControlWidget (Control Center / lock screen / Action Button)
└── WellRestTests/
```

**RestEngine**, an `@Observable` `@MainActor` class that wraps `AlarmManager.shared`:
- `start(seconds:)` cancels any active rest, then calls `AlarmManager.shared.schedule(id:configuration: .timer(duration:attributes:))`.
- `stop()` calls `AlarmManager.shared.cancel(id:)`.
- Watches `AlarmManager.shared.alarmUpdates` to mirror state into the UI and recover after relaunch.
- Asks for authorization on first start (`authorizationState` / `requestAuthorization()`) after showing the in-app explanation sheet, same pattern as Android's disclosure. Needs the `NSAlarmKitUsageDescription` Info.plist key, e.g. *"WellRest uses alarms to alert you when your rest is over, even on silent."*
- `RestMetadata: AlarmMetadata` can stay empty for v1.

**Live Activity:** the countdown on the lock screen and in the Dynamic Island is the widget extension's `ActivityConfiguration` for `AlarmAttributes<RestMetadata>`. It reads `AlarmPresentationState` and renders the remaining time with `Text(timerInterval:countsDown:)`, so no per-second updates are needed. Match the Android card: green **Rest** label, big green countdown, total ("1:00 · REST"), green round skip button (`Button(intent: StopRestIntent())`), thin green progress bar.

**Starting without opening the app:** `StartRestIntent` (an `AppIntent`, `openAppWhenRun = false`) exposed as:
1. an **App Shortcut** ("Start rest in WellRest"), for Siri and the Shortcuts app;
2. a **`ControlWidget`**, which users can place in Control Center, on the lock screen, or assign to the **Action Button**. This is the closest thing to auto-start: one press between sets, phone in pocket or on the bench.

Reference material:
- WWDC25 *Wake up to the AlarmKit API*: https://developer.apple.com/videos/play/wwdc2025/230/
- Apple sample, *Scheduling an alarm with AlarmKit*: https://developer.apple.com/documentation/alarmkit/scheduling-an-alarm-with-alarmkit
- Countdown timer walkthrough: https://nilcoalescing.com/blog/CountdownTimerWithAlarmKit/

## 4. Design

The source of truth is [`../shared/design-tokens.json`](../shared/design-tokens.json): colours, radii, type sizes, rest presets. Wording lives in [`../shared/copy.md`](../shared/copy.md). Screenshots of the Android app are in [`../docs/images`](../docs/images).

- Dark only (`.preferredColorScheme(.dark)`), background `#101418`, accent `#16EC9F`.
- Main screen, top to bottom: `WELLREST` header with gear button · status pill · 250 pt rest ring (`REST` / `1:00`) · round −15 / +15 buttons · preset chips (30s · 45s · 1:00 · 1:30 · 2:00) · a big **Start rest** button (new on iOS, since nothing starts it automatically).
- While a rest runs, the ring animates down in the app as well (`TimelineView` or `Text(timerInterval:)`).
- Haptics: `.sensoryFeedback(.selection)` on taps.
- Dialogs: use custom sheets matching the Android ones (icon badge, check-mark points, full-width green button), not `.alert`.
- App icon: `shared/brand/icon-1024-ios.png` (1024 px, opaque, iOS applies its own corner mask).

## 5. Milestones

| # | Deliverable | Done when |
|---|---|---|
| 1 | Xcode project at `ios/WellRest.xcodeproj`, scheme `WellRest`, app + widget extension + tests | `iOS CI` workflow goes green (it skips until the project exists) |
| 2 | `RestEngine` + main screen + settings persistence | Start/stop works in the app; unit tests for settings bounds (15–600 s, ±15 steps) |
| 3 | AlarmKit authorization sheet + countdown alert | Rings on a real device with the silent switch on and a Focus mode active |
| 4 | Live Activity (lock screen + Dynamic Island) with Skip | Matches the Android card; skip works from the lock screen |
| 5 | `StartRestIntent` as App Shortcut + `ControlWidget` | Can start a rest from the Action Button and Control Center without opening the app |
| 6 | Settings → About: version, privacy, licences, support link, WHOOP disclaimer | Same content as Android |
| 7 | TestFlight build | Internal testers install it |
| 8 | App Store submission | Listing, privacy label, review notes done |

## 6. Repo conventions

- iOS code lives only in `ios/`. The `iOS CI` workflow runs only on changes under `ios/`, and Android CI only on `android/`.
- Keep `shared/` in sync if colours or wording change on either platform.
- Tags: Android releases use `android-vX.Y.Z`; use **`ios-vX.Y.Z`** for iOS so the two release streams don't collide.
- Never commit signing material: `.p12`, `.p8`, `.mobileprovision` are git-ignored.

## 7. Release pipeline (to add at milestone 7)

Recommended: **fastlane** in `ios/fastlane` plus a workflow `.github/workflows/ios-release.yml` triggered by `ios-v*.*.*` tags on `macos-15`:
1. `match` or manual certificates. Simplest: an App Store Connect API key, with Xcode automatic signing via `-allowProvisioningUpdates`.
2. `xcodebuild archive` → `-exportArchive` (method `app-store-connect`).
3. Upload to TestFlight (`fastlane pilot` or `xcrun altool --upload-app`).
4. Attach nothing to the GitHub release except notes (an `.ipa` isn't installable for users).

Repository secrets to create: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (base64), `APPLE_TEAM_ID`.

## 8. App Store checklist

- **Apple Developer Program** membership ($99/year) under Sylvain's name, with the engineer invited to the team as Admin or App Manager.
- **Name:** WellRest. Don't put "WHOOP" in the name or subtitle (trademark, guideline 5.2). Mention compatibility only in the description, with the disclaimer from `shared/copy.md`.
- **Privacy nutrition label:** *Data Not Collected*.
- **Privacy policy URL:** https://goudurixx.github.io/wellrest/privacy-policy.html. Before submission, add an iOS section to `docs/privacy-policy.html`: no notification reading on iOS, AlarmKit used only for the rest alert.
- **Review notes:** explain that the app is a rest timer started manually or from the Action Button / Control Center, and that alarms are used only to signal the end of a user-started rest.
- **Category:** Health & Fitness. **Age rating:** 4+.
- **Screenshots:** 6.9" iPhone (required): main screen, Live Activity on the lock screen, Dynamic Island, Control Center control.

## 9. Open questions for product

1. Is iOS 26-only acceptable? Recommended: yes, since AlarmKit is the core of the alert.
2. Apple Watch companion (start the rest from the wrist)? Proposed for v1.1.
3. Pricing: free like Android?
