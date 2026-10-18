# SGP4 verification vectors

Byte-identical copies from python-sgp4 2.27, commit `6e7428df7acc48e8176828289a8c14486a114759`
(https://github.com/brandon-rhodes/python-sgp4, `sgp4/SGP4-VER.TLE` and `sgp4/tcppver.out`), MIT licence,
see `core/orbit/NOTICE-python-sgp4.txt`.

| File | SHA-256 |
|---|---|
| SGP4-VER.TLE | `d246d1d9d768ace445a38a965713fa9ba52d80fd8a41a0502ff83d7acffe2881` |
| tcppver.out | `687bf28dbe52df86e8e60ab5cb4a08d1aa3dbcaf4e63b1f7ab95f044fbe3833b` |

SGP4-VER.TLE has CRLF line endings; keep them (the hash covers them). Columns 70+ of each line 2 hold the
start, stop and step minutes of the verification time grid.
