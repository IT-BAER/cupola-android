# Privacy Policy: Cupola: ISS Live Wallpaper

_Last updated: 24 October 2026_

Cupola is a live wallpaper that runs entirely on your device.

## What Cupola does not do

- It contains no analytics, crash-reporting, advertising or attribution SDK.
- It has no account and no server.
- It does not request location, camera, microphone, contacts, storage or any other runtime permission.

## Data stored on your device

- Your settings (rest view, look-around strength, idle float, effects) and the home city you pick from the built-in list are stored in the app's private storage. They never leave your device: Android cloud backup and device-to-device transfer are turned off for this app.
- The orbit data of the space station ships inside the app and is updated with app updates. If you allow it (see below), a newer copy downloaded from the Cupola website is stored in the app's private storage.
- The motion sensor (game rotation vector) is read only while the wallpaper is visible, to let you look around. The readings stay in memory and are not stored.

## Orbit data download

To keep the space station's position accurate between app updates, Cupola downloads two small public files, `iss.tle` and `iss.json`, from `https://it-baer.github.io/cupola-android/orbit/`. These are the station's orbital elements, published by US Space Force via CelesTrak and mirrored on the project's GitHub Pages site.

- This happens at most once a day and only while the wallpaper is visible. Cupola sends no personal data, no identifier and no settings; the request contains only what any web request carries, such as your IP address, which GitHub, the site host, receives. GitHub's privacy statement applies to that: <https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement>.
- The files are checked before use; anything invalid or older than the data in use is discarded.
- You can turn this off with "Update orbit data online" in the settings. Cupola then uses the orbit data that came with the app.

This download is the app's only network use, and the internet permission exists only for it.

## Sharing a picture

When you tap Share, Cupola renders the current view into a PNG file in the app's cache folder and hands it to the app you choose in the Android share sheet. Nothing is shared without your action.

## Google Play

Google Play handles the purchase and the app's installation and updates. Google's privacy policy applies to that: <https://policies.google.com/privacy>. Crash and ANR statistics that you allow Google to collect appear to the developer only as anonymous Android vitals.

## Deleting your data

Uninstalling the app, or clearing its storage in Android settings, removes all data the app stored.

## Contact

IT-BAER. Open an issue at <https://github.com/IT-BAER/cupola-android/issues>, or write to the developer email address shown on the app's Google Play page.
