# Blockzeit

Blockzeit is an Android home screen widget. It requires Android 12 or newer.

## Modes

- **Clock:** time.
- **Bitcoin:** Bitcoin block height.
- **Price:** Bitcoin price.
- **Weather:** weather forecast for a chosen location.
- **Calendar:** upcoming events from a selected device calendar.

Add the widget to your home screen. Use the app to choose a mode and adjust its appearance.
Calendar access is needed for calendar events. Bitcoin and weather data need an internet connection.

## Build

Requires JDK 17, Gradle 8.4 and Android SDK 34 with Build Tools 34.0.0.
Set `ANDROID_HOME` to your SDK directory, then run:

```sh
gradle --no-daemon :app:assembleDebug
```

## Screenshots

See [assets](assets/).

## License

[GNU General Public License v3.0](LICENSE).
