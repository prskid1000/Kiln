package app.kiln

import android.app.Application
import app.kiln.agent.Kiln
import app.kiln.agent.SettingsStore
import app.kiln.build.BuildEngine
import app.kiln.core.Paths
import app.kiln.device.Device
import app.kiln.device.TestDisplay
import app.kiln.tools.Skills
import app.kiln.device.Warden
import app.kiln.llm.Providers
import app.kiln.llm.Secrets
import app.kiln.toolchain.ToolHost
import app.kiln.toolchain.Toolchain
import app.kiln.build.AppSecrets

class KilnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** The app's singletons. */
object Graph {
    lateinit var app: Application; private set
    lateinit var paths: Paths; private set
    lateinit var toolchain: Toolchain; private set
    lateinit var toolHost: ToolHost; private set
    lateinit var builds: BuildEngine; private set
    lateinit var warden: Warden; private set
    lateinit var device: Device; private set
    /** The agent's device: like [device], but launch/input/screens target an invisible test display. */
    lateinit var testDevice: Device; private set
    lateinit var secrets: Secrets; private set
    lateinit var providers: Providers; private set
    lateinit var settings: SettingsStore; private set
    lateinit var kiln: Kiln; private set

    fun init(app: Application) {
        if (::app.isInitialized) return
        this.app = app
        paths = Paths(app)
        toolchain = Toolchain(paths)
        toolHost = ToolHost(paths, toolchain)
        builds = BuildEngine(toolchain, toolHost) { p, name -> secrets.get(AppSecrets.storeId(p, name)) }
        toolchain.beforeSwitch = { toolHost.shutdown() }
        warden = Warden(app)
        device = Device(warden)
        testDevice = Device(warden, TestDisplay(app))
        secrets = Secrets(app)
        providers = Providers(paths.files, secrets)
        settings = SettingsStore(paths.files)
        kiln = Kiln(paths, toolchain, builds, warden, device, testDevice, providers, settings, Skills(app))
    }
}
