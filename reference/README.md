# Reference photos (plan P4.1)

`dev-set.json` lists the development reference photos: public-domain NASA ISS crew photos from
images.nasa.gov, used for P3a, P4.7 review rounds and goldens. The image files live in the
gitignored cache `local/reference/dev/` (bytes and SHA-256 in the JSON).

The G-PR hold-out set is deliberately not listed in the repository (decision D2): its photos and
IDs stay in `local/reference/holdout/` and are never shown to the owner before the G-PR run.

`exif_datetime_original` is the camera clock as stored (ISS cameras normally run on GMT; not
verified per photo). `eol` (nadir point, sun angles from eol.jsc.nasa.gov) is null while the EOL
photo query is unavailable.