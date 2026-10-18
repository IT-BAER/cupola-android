package com.baer.cupola.tools.geoindex

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GeneratorDeterminismTest {
    @Test fun twoRunsAreByteIdentical() {
        val sources = File(checkNotNull(System.getProperty("geoindex.sources")))
        val cities = File(sources, "geonames/cities15000.zip")
        val marine = File(sources, "naturalearth/ne_10m_geography_marine_polys.zip")
        assumeTrue("sources not mirrored under $sources", cities.isFile && marine.isFile)

        val a = Files.createTempDirectory("geoindex-a").toFile()
        val b = Files.createTempDirectory("geoindex-b").toFile()
        try {
            for (dir in listOf(a, b)) {
                CityIndexWriter.write(cities, dir)
                MarineRasterWriter.write(marine, dir)
            }
            for (name in listOf("cities.bin", "marine.bin")) {
                assertTrue("$name empty", File(a, name).length() > 0)
                assertArrayEquals(name, File(a, name).readBytes(), File(b, name).readBytes())
            }
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }
}
