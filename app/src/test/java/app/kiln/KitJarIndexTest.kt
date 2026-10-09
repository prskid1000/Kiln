package app.kiln

import app.kiln.tools.classPublicStatics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** The packed kit jar (what the phone compiles against) yields every kit function by name. */
class KitJarIndexTest {
    @Test fun packedKitJarHasAllComponents() {
        val jar = File("../toolchain/out/pack/kit/classpath/app.kiln__kit__local.jar")
        assumeTrue("pack not built", jar.isFile)
        val names = HashSet<String>()
        val failed = mutableListOf<String>()
        ZipFile(jar).use { z ->
            for (e in z.entries()) if (e.name.startsWith("app/kiln/kit/") && e.name.endsWith("Kt.class") && '$' !in e.name) {
                val r = z.getInputStream(e).use { classPublicStatics(it.readBytes()) }
                if (r.isEmpty()) failed += e.name
                names += r
            }
        }
        assertTrue("facades read as empty: $failed", failed.isEmpty())
        for (n in listOf("KCardBox", "KDivider", "KButton", "KilnScreen", "KToastHost", "rememberKToast"))
            assertTrue("$n missing from ${names.size} names", n in names)
    }
}
