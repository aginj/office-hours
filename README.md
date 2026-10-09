# Office Hours

Android app that reads your greytHR attendance swipes and shows how much of the required day is done, how much break still fits before the shift ends, and a week and month summary.

It is a personal client for the greytHR website. Credentials stay on the phone, encrypted, and are used only to sign in again when the session expires.

## What it shows

- Today: progress ring, remaining time, live on-break timer, late start, and a swipe timeline
- Whether you can still leave at the end of the shift, or how short the day will be
- This week (Mon–Fri) and the current month, with leave, holiday, and week-off markers when greytHR returns them
- A home-screen widget, including a compact size
- Alerts when required hours are done, when the break budget for shift end hits zero, and 15 minutes before shift end if the day is still short

Shift start, end, and required hours are editable. The default is 9:00 AM–6:00 PM and 7 hours, in India time (`Asia/Kolkata`).

## Requirements

- Android 8.0 (API 26) or newer
- A greytHR company address, employee number, and password
- Notification permission, and Alarms & reminders if you want the alerts to fire on time

## Build

You need JDK 17 and the Android SDK (compile SDK 35). Android Studio creates `local.properties` with `sdk.dir` when you open the project. That file is gitignored.

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Project layout

| Path | Role |
| --- | --- |
| `app/src/main/java/app/officehours/data` | greytHR client, attendance maths, encrypted credential store |
| `app/src/main/java/app/officehours/ui` | Compose screens |
| `app/src/main/java/app/officehours/widget` | Home-screen widget |
| `app/src/main/java/app/officehours/alerts` | Exact alarms and notifications |

## Notes

- greytHR can ask for a password reset or an extra verification step. Those have to be finished on the website; this app cannot complete them.
- Leave and holiday labels depend on the shape of the leave-calendar response and may be missing for some companies. Attendance still loads.
- Do not commit `local.properties`, keystores, or anything under `app/build`.

## License

No license file is included yet. All rights reserved until one is added.
