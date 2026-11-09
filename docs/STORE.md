# Google Play submission notes — WellRest

## Before the first upload
1. **App name: WellRest** (no third-party trademark in the title). WHOOP is mentioned only in the
   description, always with the non-affiliation disclaimer.
2. **applicationId** `com.sylvainrousseau.wellrest` is permanent once published — change it now if wanted.
3. Privacy policy: https://goudurixx.github.io/wellrest/privacy-policy.html (GitHub Pages, branch `main`, folder `/docs`).
   Support email: sylvain.rousseau.apps@gmail.com
4. Create the upload key and add the repository secrets listed in the README, then release by
   pushing a tag (`git tag v1.5.0 && git push origin v1.5.0`). The first AAB must be uploaded to
   Play Console manually; later ones go to the internal track automatically once
   `PLAY_SERVICE_ACCOUNT_JSON` is set.

## Play Console → App content
**Privacy policy:** the hosted URL above.

**Data safety:** no data collected, no data shared.
- Notification content is processed only on the device and never transmitted (no INTERNET permission),
  so it is not "collected" under Play's definition.
- Encryption in transit: not applicable (no transmission). Data deletion: uninstall removes everything.

**Ads:** no. **Target audience:** 18+ (or 13+), not designed for children.
**Content rating:** questionnaire → utility, no user content → Everyone / PEGI 3.
**Health apps declaration:** fitness utility, no health data collected.

**Foreground service — type `specialUse`** (required, with a video):
- Description: "When the user finishes a set in their WHOOP strength workout, the app runs a
  user-visible rest countdown (default 60 s, max 10 min) in an ongoing notification with a Skip
  button, then plays one alert sound. The service stops as soon as the countdown ends or is skipped."
- Impact if deferred/interrupted: "The rest countdown would freeze or disappear and the user would
  not be alerted when their rest period is over, which is the core purpose of the app."
- Why no other type fits: not media playback, location, health-sensor or data sync; the countdown
  can last longer than the 3-minute `shortService` limit.
- Video (≤ 1 min, screen recording): open the app → start a WHOOP workout → finish a set → rest
  notification appears and counts down → it rings → Skip button stops it.

## Notification access
There is no Play declaration form for NotificationListenerService, but the User Data policy requires
the in-app prominent disclosure (implemented: shown on first launch and before opening the system
screen). Reviewers may ask why: "Core functionality — detects the end of a set from the WHOOP app's
workout notification to start the rest timer automatically. Other apps' notifications are ignored."

## Listing draft
**Short description (80):** Automatic rest timer that starts when your WHOOP set ends.

**Full description:**
Never lose track of your rest again. WellRest watches the WHOOP Strength Trainer workout
notification on your phone and starts a rest countdown the moment you finish a set. When your
rest is over, it rings once — even with your phone on silent — so you're ready for the next set.

• Starts automatically at the end of each set
• Shows the next exercise and a live countdown in your notification shade
• Adjustable rest: 15 s to 10 min, quick presets
• Cancels itself if you start your next set early
• Private by design: works entirely on your phone, no internet access, no account, no ads

Requires the WHOOP app with a Strength Trainer workout in progress. WellRest is an independent
app and is not affiliated with, endorsed or sponsored by WHOOP, Inc. WHOOP is a trademark of WHOOP, Inc.

## Testing (new personal developer accounts)
New personal Play accounts must run a closed test with at least 12 testers for 14 days before
applying for production access.
