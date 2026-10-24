# Cupola: ISS Live Wallpaper

An Android live wallpaper that shows the Earth from inside the Cupola, the seven-window observation module of the
International Space Station, on a realistic orbit computed on the phone.

- Orbit: SGP4 on bundled orbit data (refreshed per release), with day and night, sunrise and sunset.
- Earth: NASA Blue Marble colours by month, relief, ocean glint, an atmosphere computed from lookup tables, clouds
  that drift and change over days, city lights, moonlight, aurora, lightning, airglow, the Moon and the stars.
- Cabin: a baked panorama of the Cupola interior; look around by tilting the phone; two rest views (diagonal, nadir).
- Effects (Settings): bloom, window glare, glass reflections, dust.
- Info screen: the place below the station, its local time, the next pass over a home city, which is also marked on
  the wallpaper when it is in view.
- No internet permission, no ads, no account, no tracking.

Requires Android 13 (API 33) or newer and Vulkan 1.1.

## Modules

| Module | Content |
|---|---|
| `:app` | Wallpaper service, settings Activity, render host and sessions |
| `:core:render` | Vulkan renderer (C++, `src/main/cpp`, shader `shaders/earth.frag`) and the scene inputs |
| `:core:orbit` | SGP4, frames, Sun and Moon, pass prediction |
| `:feature:settings` | Settings, Info, home city search, credits (Compose) |
| `:earthpack` | Install-time asset pack with the Earth and cabin textures |
| `:tools:tle`, `:tools:geoindex` | Build-time orbit data and city index tools |

Texture and panorama contracts: `tools/textures/README.md`, `tools/blender/README.md`. Plan: `docs/plan.md`.

## Build and test

```bash
./gradlew build
```

```bash
./gradlew testDebugUnitTest
```

Native host tests (KTX2 reader, atmosphere lookup tables, sky lookup-table reuse):

```bash
cmake -S core/render/src/test/cpp -B build/host-tests -G Ninja && cmake --build build/host-tests && ctest --test-dir build/host-tests
```

The instrumented tests (`EarthRenderTest`, goldens and seam sweep included) need the app installed from the bundle
(`bundletool install-apks`), because plain APKs carry no asset pack. Goldens are device renders in
`app/src/androidTest/assets/goldens`.

## Credits

NASA Earth Observatory (Blue Marble, Black Marble), NOAA ETOPO 2022, ESO (Milky Way panorama), GeoNames, Natural
Earth, CelesTrak / US Space Force orbit data; the full list ships in the app (Credits screen,
`app/src/main/assets/credits.tsv`).
