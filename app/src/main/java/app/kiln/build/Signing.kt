package app.kiln.build

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date

/**
 * One signing key per project, created on first build and kept in
 * .kiln/signing.p12 (password beside it). The same key every build, so an app
 * can be reinstalled over itself without losing its data.
 */
object Signing {
    data class Key(val keystore: File, val password: String, val alias: String = "kiln")

    fun ensure(project: Project): Key {
        val ks = File(project.kilnDir, "signing.p12")
        val pw = File(project.kilnDir, "signing.pass")
        if (ks.isFile && pw.isFile) return Key(ks, pw.readText().trim())
        // Half a key is not "no key": regenerating would change the app's signature and the next
        // install would have to wipe its data. Stop instead.
        check(!ks.isFile) { "the project's signing key is incomplete (.kiln/signing.*) — restore it from a backup" }
        project.kilnDir.mkdirs()
        val password = BigInteger(96, SecureRandom()).toString(36)
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val name = X500Name("CN=${project.meta().label.replace(Regex("[,=+<>#;\"\\\\]"), " ")}, O=Kiln")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("kiln", kp.private, password.toCharArray(), arrayOf(cert))
        // Both files land atomically (temp + rename), password first: a crash leaves either nothing or both.
        val ksTmp = File(project.kilnDir, "signing.p12.tmp"); val pwTmp = File(project.kilnDir, "signing.pass.tmp")
        ksTmp.outputStream().use { store.store(it, password.toCharArray()); it.fd.sync() }
        pwTmp.writeText(password)
        check(pwTmp.renameTo(pw) && ksTmp.renameTo(ks)) { "could not save the signing key" }
        return Key(ks, password)
    }
}
