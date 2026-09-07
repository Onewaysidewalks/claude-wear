# Phase 0: does holding the button reach us?

This is the one question that changes the plan. The spike answers it on **your** watch and
firmware; nothing in this repository assumes the answer.

## What the spike is

`android/spike` builds two Wear OS APKs that can be installed side by side:

| flavor | package | registers |
| --- | --- | --- |
| `assist` | `dev.claudewear.spike.assist` | an activity for `ACTION_ASSIST` + `VOICE_ASSIST` (exactly what Home Assistant's Wear app ships, which is known to appear in the Wear "Digital assistant app" picker) |
| `voice` | `dev.claudewear.spike.voice` | the same, plus a `VoiceInteractionService`, its session service, and a stub `RecognitionService` (what a phone assistant registers) |

Both log every way the system can reach them, on screen and in logcat (`HermesSpike`):
`ASSIST_ACTIVITY` (the assist intent arrived, with action and extras), `VIS` (the service was
bound; only happens when we hold the role), `VIS_SESSION onShow` (invoked through the service,
with decoded source flags), `KEY` (any stem key that reached the foreground activity),
`ROLE_REQUEST` (what `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` did), and the
current values of `Settings.Secure.assistant` / `voice_interaction_service`.

## What we know going in (sources)

* Wear OS has **Settings > Apps > Choose default apps > Digital assistant app**; Home
  Assistant's Wear app appears there with nothing but an `ACTION_ASSIST` activity
  (https://www.home-assistant.io/voice_control/android/ and its manifest
  https://raw.githubusercontent.com/home-assistant/android/main/wear/src/main/AndroidManifest.xml).
* Home Assistant's docs for Galaxy Watch (Wear OS 4): *Settings > Buttons and gestures >
  Press and hold > Assistant*, then hold the key and pick **HA: Assist** from a chooser,
  "Always". That is a chooser, not the role: Samsung appears to fire the assist intent and let
  the resolver pick.
* Samsung's support pages list only Bixby / Google Assistant / Gemini for press-and-hold and say
  Alexa cannot be assigned (https://www.samsung.com/us/support/answer/ANS10003405/). Double
  press accepts "any app" (https://www.samsung.com/us/support/answer/ANS10003380/).
* Samsung says the Home/Back keys on Watch4+ are system keys not delivered to apps; Wear docs
  say only `KEYCODE_STEM_1..3` ever reach an app and the primary button never does
  (https://developer.android.com/training/wearables/user-input/physical-buttons).
* Gemini on Wear is `com.google.android.wearable.assistant`; Bixby is
  `com.samsung.android.bixby.agent`.
* **Unknown**: whether One UI Watch 7/8 with Gemini preinstalled still offers a chooser.

## Run it

```bash
make spike
adb connect <watch-ip>:5555          # Wear: Settings > Developer options > Wireless debugging
adb install -r android/spike/build/outputs/apk/assist/debug/spike-assist-debug.apk
adb install -r android/spike/build/outputs/apk/voice/debug/spike-voice-debug.apk
adb logcat -s HermesSpike &
```

Record the answers to each step in the table at the bottom.

1. **Baseline.** Open *Spike (assist)*. Write down the device line (model, Android version,
   build) and the `assistant:` / `vis:` values. Then:
   ```bash
   adb shell cmd role get-role-holders android.app.role.ASSISTANT
   adb shell settings get secure assistant
   adb shell settings get secure voice_interaction_service
   adb shell pm list packages | grep -iE 'assistant|bixby|googlequicksearchbox'
   ```
2. **Is there a picker?** Watch: *Settings > Apps > Choose default apps* (or *Default apps*).
   Is there a *Digital assistant app* entry? Which apps does it list: `Spike (assist)`,
   `Spike (voice)`, both, neither? Pick `Spike (assist)`. Re-open the spike: does `role held`
   read `true`? Does `assistant:` now name our package? Run the adb lines again.
3. **Press and hold.** Watch: *Settings > Advanced features / Buttons and gestures > Press and
   hold Home key*. Write down every option offered. If there is an *Assistant* (or similar
   generic) option, choose it. Hold the button. Did a chooser appear? Did our app open? Which
   log line appeared: `ASSIST_ACTIVITY` or `VIS_SESSION`? What action and extras did it carry?
4. **Repeat step 2 and 3 with `Spike (voice)`** as the chosen assistant. Does the `VIS onReady`
   line appear (proves the role really binds our service)? Does holding the button produce
   `VIS_SESSION onShow` with `SOURCE_ASSIST_GESTURE` or similar?
5. **Request role from the app.** Tap *Request role* in either flavor. The log shows whether
   Samsung lets an app request `ROLE_ASSISTANT` (stock Android does not; expect `code=0`).
6. **Fallback check regardless of 3/4.** *Press and hold* has no assistant route? Set
   *Double press Home key* to `Spike (assist)` and double press: the `MAIN onCreate` line proves
   that path. Also note whether any `KEY` lines appear when pressing buttons with the spike in
   the foreground (expect none for Home/Back).
7. **Force it, to learn what the setting reads** (may fail without shell permission; that is
   itself an answer):
   ```bash
   adb shell cmd role add-role-holder android.app.role.ASSISTANT dev.claudewear.spike.voice
   adb shell settings put secure assistant dev.claudewear.spike.voice/dev.claudewear.spike.AssistActivity
   adb shell settings put secure voice_interaction_service dev.claudewear.spike.voice/dev.claudewear.spike.SpikeVoiceInteractionService
   adb shell settings list secure | grep -iE 'assist|voice_inter|hold|bixby'
   adb shell settings list system | grep -iE 'assist|hold|bixby'
   ```
   Hold the button again after each change.

Uninstall when done: `adb uninstall dev.claudewear.spike.assist dev.claudewear.spike.voice`.

## Decide

| observation | decision for M5 |
| --- | --- |
| Hold → chooser or direct launch, `ASSIST_ACTIVITY` logged, with `assist` flavor | Keep only the `AssistEntry` alias in `android/watch`. Done. |
| Hold works only with `voice` flavor (`VIS_SESSION` logged) | Copy the spike's `voice` services into `android/watch`; start listening from `VoiceInteractionSession.onShow`. |
| No picker entry, or hold offers only Bixby/Google/Gemini with no chooser | Fall back: **double press** → watch app (auto-listens on launch). Then Tile. Document which, and stop spending time on the role. |
| Picker exists but Samsung's hold ignores it even when we hold the role | Same fallback; note the firmware version so a later One UI can be re-tested. |

## Results (fill in)

| item | value |
| --- | --- |
| watch model / One UI Watch / Android / build | |
| picker present? apps listed? | |
| press-and-hold options offered | |
| hold with `assist`: log line, action, extras | |
| hold with `voice`: log line, flags | |
| role request result | |
| double press works? | |
| `settings list` keys that changed | |
| decision | |
