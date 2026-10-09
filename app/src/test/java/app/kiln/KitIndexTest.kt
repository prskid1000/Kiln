package app.kiln

import app.kiln.tools.classPublicStatics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The import fixer finds kit functions by their real names, not by the file they live in. */
class KitIndexTest {
    private fun facade(name: String): File? =
        File("../kit/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/app/kiln/kit/$name.class").takeIf { it.isFile }

    @Test fun readsTopLevelFunctionsOfKitFiles() {
        val scaffolds = facade("ScaffoldsKt"); val overlays = facade("KitOverlaysKt")
        assumeTrue("kit not compiled", scaffolds != null && overlays != null)
        val s = classPublicStatics(scaffolds!!.readBytes())
        assertTrue(s.toString(), "KilnScreen" in s)
        val o = classPublicStatics(overlays!!.readBytes())
        assertTrue(o.toString(), "KToastHost" in o && "rememberKToast" in o)
        assertTrue(o.toString(), o.none { '$' in it || '<' in it })
    }
}
