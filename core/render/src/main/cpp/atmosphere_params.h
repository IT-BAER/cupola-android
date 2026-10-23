// Atmosphere model shared by atmosphere_lut.cpp (C++) and earth.frag (GLSL): plain #defines only.
// Hillaire 2020 Earth parameters, km and 1/km; lengths in the ellipsoid-scaled frame (z * a / b), where the
// WGS-84 surface is the sphere of radius ATMO_BOTTOM_KM.
#ifndef CUPOLA_ATMOSPHERE_PARAMS_H
#define CUPOLA_ATMOSPHERE_PARAMS_H

#define ATMO_BOTTOM_KM 6378.137
#define ATMO_TOP_KM 6478.137

#define ATMO_RAYLEIGH_SCATTER_R 5.802e-3
#define ATMO_RAYLEIGH_SCATTER_G 13.558e-3
#define ATMO_RAYLEIGH_SCATTER_B 33.1e-3
#define ATMO_RAYLEIGH_HEIGHT_KM 8.0

#define ATMO_MIE_SCATTER 3.996e-3
#define ATMO_MIE_EXTINCTION 4.440e-3
#define ATMO_MIE_HEIGHT_KM 1.2
#define ATMO_MIE_G 0.8

// Ozone: absorption only, tent profile peaking at ATMO_OZONE_CENTER_KM, zero ATMO_OZONE_HALF_WIDTH_KM either side.
#define ATMO_OZONE_ABSORB_R 0.650e-3
#define ATMO_OZONE_ABSORB_G 1.881e-3
#define ATMO_OZONE_ABSORB_B 0.085e-3
#define ATMO_OZONE_CENTER_KM 25.0
#define ATMO_OZONE_HALF_WIDTH_KM 15.0

#define ATMO_GROUND_ALBEDO 0.3

// LUT sizes: transmittance (x = view zenith, y = altitude), multiple scattering (x = sun zenith, y = altitude),
// sky irradiance on the ground (x = sun zenith), per-frame view LUT of the space camera (x = azimuth, y = nadir).
#define ATMO_TRANSMITTANCE_W 256
#define ATMO_TRANSMITTANCE_H 64
#define ATMO_MULTI_SCATTER_N 32
#define ATMO_IRRADIANCE_W 64
#define ATMO_VIEW_W 96
#define ATMO_VIEW_H 256

#endif
