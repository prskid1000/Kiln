package app.kiln.build

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Builds the final AndroidManifest.xml: the project's manifest, plus what
 * kiln.json says (package, versions, SDKs, permissions), plus the kit's merged
 * library fragment (startup provider, WorkManager components, permissions).
 * `${applicationId}` placeholders become the package name.
 */
object ManifestMerger {
    private const val A = "http://schemas.android.com/apk/res/android"

    fun merge(appManifest: File, kitFragment: File?, meta: ProjectMeta): String {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(appManifest)
        val root = doc.documentElement
        root.setAttribute("package", meta.`package`)
        root.setAttributeNS(A, "android:versionCode", meta.versionCode.toString())
        root.setAttributeNS(A, "android:versionName", meta.versionName)

        children(root, "uses-sdk").forEach { root.removeChild(it) }
        val sdk = doc.createElement("uses-sdk")
        sdk.setAttributeNS(A, "android:minSdkVersion", meta.minSdk.toString())
        sdk.setAttributeNS(A, "android:targetSdkVersion", meta.targetSdk.toString())
        root.insertBefore(sdk, root.firstChild)

        var app = children(root, "application").firstOrNull()
        if (app == null) { app = doc.createElement("application"); root.appendChild(app) }

        val havePerms = children(root, "uses-permission").map { it.getAttributeNS(A, "name") }.toMutableSet()
        fun addPermission(name: String) {
            if (name in havePerms) return
            havePerms += name
            val p = doc.createElement("uses-permission")
            p.setAttributeNS(A, "android:name", name)
            root.insertBefore(p, app)
        }
        meta.permissions.forEach { addPermission(if ('.' in it) it else "android.permission.$it") }

        if (kitFragment != null && kitFragment.isFile) {
            val frag = f.newDocumentBuilder().parse(
                kitFragment.readText().replace("\${applicationId}", meta.`package`).byteInputStream())
            val fr = frag.documentElement
            // Play Billing (KBilling) only for apps that opted in with the BILLING permission:
            // otherwise every app would ask for it and carry Play's billing components.
            val billing = BILLING in havePerms
            fun isBilling(e: Element) = !billing && (e.getAttributeNS(A, "name").let { it == BILLING || "billingclient" in it } ||
                "InAppBillingService" in serializeNode(e))
            for (el in elements(fr)) if (!isBilling(el)) when (el.tagName) {
                "uses-permission" -> addPermission(el.getAttributeNS(A, "name"))
                "application" -> {
                    val have = elements(app!!).map { it.tagName to it.getAttributeNS(A, "name") }.toSet()
                    for (c in elements(el)) if ((c.tagName to c.getAttributeNS(A, "name")) !in have && !isBilling(c))
                        app.appendChild(doc.importNode(c, true))
                }
                "queries" -> {
                    val q = doc.importNode(el, true) as Element
                    elements(q).filter { isBilling(it) }.forEach { q.removeChild(it) }
                    if (elements(q).isNotEmpty()) root.insertBefore(q, app)
                }
                else -> root.insertBefore(doc.importNode(el, true), app)
            }
        }
        // Development builds report crashes to Kiln (the kit's KilnCrash), which Android only
        // delivers to a package the app can see.
        if (elements(root).none { it.tagName == "queries" && elements(it).any { p -> p.getAttributeNS(A, "name") == KILN } }) {
            val q = doc.createElement("queries")
            val p = doc.createElement("package"); p.setAttributeNS(A, "android:name", KILN)
            q.appendChild(p); root.insertBefore(q, app)
        }
        return serialize(doc)
    }

    private const val KILN = "app.kiln"
    private const val BILLING = "com.android.vending.BILLING"

    private fun serializeNode(e: Element): String {
        val w = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(e), StreamResult(w))
        return w.toString()
    }

    private fun elements(e: Element): List<Element> =
        (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }

    private fun children(e: Element, tag: String) = elements(e).filter { it.tagName == tag }

    private fun serialize(doc: Document): String {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        val w = StringWriter()
        t.transform(DOMSource(doc), StreamResult(w))
        return w.toString()
    }
}
