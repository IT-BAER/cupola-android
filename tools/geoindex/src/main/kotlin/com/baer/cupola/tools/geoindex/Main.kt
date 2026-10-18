package com.baer.cupola.tools.geoindex

import java.io.File
import kotlin.system.exitProcess

/** Usage: geoindex <cities15000.zip> <ne_10m_geography_marine_polys.zip> <outDir> */
fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println("usage: geoindex <cities15000.zip> <marine_polys.zip> <outDir>")
        exitProcess(2)
    }
    val out = File(args[2])
    val cities = CityIndexWriter.write(File(args[0]), out)
    val sea = MarineRasterWriter.write(File(args[1]), out)
    println("cities.bin ${cities.first} bytes (${cities.second} cities, ${cities.third} aliases)")
    println("marine.bin $sea bytes")
}
