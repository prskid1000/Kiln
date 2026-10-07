package app.kiln

import android.app.Application
import app.kiln.build.BuildEngine
import app.kiln.core.Paths
import app.kiln.toolchain.ToolHost
import app.kiln.toolchain.Toolchain

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

    fun init(app: Application) {
        if (::app.isInitialized) return
        this.app = app
        paths = Paths(app)
        toolchain = Toolchain(paths)
        toolHost = ToolHost(paths, toolchain)
        builds = BuildEngine(toolchain, toolHost)
    }
}
