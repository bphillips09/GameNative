package app.gamenative.service.xbox

import android.content.Context
import android.os.Build
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.XboxGame
import app.gamenative.db.dao.XboxGameDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.download.GameDownloadService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.core.envvars.EnvVars
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * GameNative's Xbox store: Microsoft Store PC games (Xbox Play Anywhere, PC Game
 * Pass and other owned PC titles) through the Xodus Nexus backend.
 *
 *  sign-in   → [XboxOAuthActivity] + [XboxSignIn]
 *  library   → [refreshLibrary]: entitlements from the backend, display data from the Store catalog
 *  install   → [install]: package resolution + streaming download/extraction
 *  launch    → [prepareLaunch] / [finishLaunch], called from XServerScreen
 */
object XboxService {
    private const val TAG = "Xbox"

    @Volatile private var appContext: Context? = null
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "xbox-worker").apply { isDaemon = true } }

    val paths: XodusPaths
        get() {
            val context = requireNotNull(appContext) { "XboxService.init was not called" }
            return XodusPaths(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                dataDir = File(context.filesDir, "xbox"),
                cacheDir = File(context.cacheDir, "xbox"),
            )
        }

    val backend: XodusBackend
        get() = XodusBackend(paths, paths.environment(Build.MANUFACTURER ?: "Android", Build.MODEL ?: "Device"))

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface XboxDaoEntryPoint {
        fun xboxGameDao(): XboxGameDao
    }

    private val library: XboxLibraryStore by lazy {
        val context = requireNotNull(appContext) { "XboxService.init was not called" }
        val dao = EntryPointAccessors.fromApplication(context, XboxDaoEntryPoint::class.java).xboxGameDao()
        XboxLibraryStore(dao).also { store ->
            // Builds before the Room table kept the library in this file.
            val imported = store.importLegacy(File(paths.dataDir, "library.json"))
            if (imported > 0) Timber.tag(TAG).i("Imported $imported Xbox games into the database")
            store.reconcileInstalls().forEach { Timber.tag(TAG).i("${it.productId} files are gone; marked uninstalled") }
        }
    }

    var market: String = XboxConstants.DEFAULT_MARKET

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        executor.execute { runCatching { XboxCaBundle.ensure(paths.caBundle) } }
    }

    val isAvailable: Boolean get() = appContext != null && paths.cli.isFile && paths.service.isFile

    /**
     * Whether this device has the Xodus backend, which is built for arm64 only (32-bit
     * installs of the legacy flavor have none). Checked like GogGalaxyService checks
     * libcomet.so; without it the Xbox store is hidden.
     */
    fun isSupported(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, XboxConstants.CLI_BINARY).exists()

    /**
     * Whether a Microsoft account is signed in, without starting the backend. The secrets
     * directory cannot answer this (Xodus keeps device identity there across sign-outs), so
     * the account name [refreshAccount] last read is cached in prefs.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hasStoredCredentials(context: Context): Boolean = PrefManager.xboxAccountName.isNotEmpty()

    @Volatile private var syncing = false

    /**
     * Re-reads the account, then refreshes the library, in the background; at most one
     * refresh runs at a time. Reading the account first lets the sync after sign-in run
     * before the cached sign-in state is set.
     */
    fun triggerLibrarySync(context: Context) {
        init(context)
        if (syncing) return
        syncing = true
        executor.execute {
            try {
                if (!refreshAccount().stored) return@execute
                val games = refreshLibrary()
                Timber.tag(TAG).i("Xbox library synced: ${games.size} games")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Xbox library sync failed")
            } finally {
                syncing = false
            }
        }
    }

    /**
     * Fills the cached sign-in state on start-up when it is unknown (installs from before it
     * existed). Call after PrefManager.init.
     */
    fun restoreAccountState(context: Context) {
        init(context)
        if (hasStoredCredentials(context)) return
        // Only when Xodus has ever stored a token, so non-Xbox users never start the backend.
        val secrets = File(context.filesDir, "xbox/secrets").listFiles()
        if (secrets?.any { it.name.endsWith(".secret") } != true) return
        executor.execute {
            runCatching { refreshAccount() }
                .onSuccess { if (it.stored) triggerLibrarySync(context) }
                .onFailure { Timber.tag(TAG).w(it, "Xbox account check failed") }
        }
    }

    // ------------------------------------------------------------------ account

    @Volatile var account: XodusProtocol.Account? = null
        private set

    fun refreshAccount(): XodusProtocol.Account {
        XboxCaBundle.ensure(paths.caBundle)
        return backend.account().also {
            account = it
            PrefManager.xboxAccountName = if (it.stored) it.username.orEmpty() else ""
            notifyChanged()
        }
    }

    fun signOut() {
        runCatching { backend.logout() }.onFailure { Timber.tag(TAG).w(it, "logout failed") }
        account = XodusProtocol.Account(false, null)
        PrefManager.xboxAccountName = ""
        notifyChanged()
    }

    /** Signs out and removes games that are not installed, as the other stores do. */
    suspend fun logout(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            init(context)
            signOut()
            EntryPointAccessors.fromApplication(context.applicationContext, XboxDaoEntryPoint::class.java)
                .xboxGameDao()
                .deleteAllNonInstalledGames()
            Timber.tag(TAG).i("Signed out of Xbox; non-installed games removed")
        }
    }

    // ------------------------------------------------------------------ library

    fun games(): List<XboxGame> = library.all()
    fun gameByAppId(appId: Int): XboxGame? = library.byAppId(appId)
    fun gameByProductId(productId: String): XboxGame? = library.byProductId(productId)

    /** The library as Room emits it, for the library UI. */
    fun gamesFlow(): Flow<List<XboxGame>> = library.flow()

    /**
     * Reloads entitlements and catalog data. Only playable PC titles are kept:
     * the account must hold a valid license (owned or subscription) and the
     * Store must list a Windows.Desktop package for the product.
     */
    fun refreshLibrary(): List<XboxGame> {
        val current = account?.takeIf { it.stored } ?: refreshAccount()
        val username = current.username ?: throw XodusException("Sign in with a Microsoft account first.")
        val entitlements = backend.library(username, market)
        val access = entitlements.access()
        val catalog = runCatching { XboxCatalog.fetch(access.keys, market) }
            .onFailure { Timber.tag(TAG).w(it, "Store catalog unavailable") }
            .getOrDefault(emptyMap())
        for ((productId, kind) in access) {
            val product = catalog[productId]
            // Unknown to the catalog, add-ons, passes and console-only products are skipped.
            if (product == null || !product.hasPcPackage || product.productType?.equals("Game", ignoreCase = true) == false) continue
            library.upsertFromLibrary(productId, kind, product)
        }
        // Keep installed games visible even if the entitlement lapsed (launch re-checks the license).
        library.clearAccessExcept(access.keys)
        notifyChanged()
        return library.all()
    }

    // ------------------------------------------------------------------ install

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active downloads by product ID, as AmazonService keeps them. */
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    fun getDownloadInfo(productId: String): DownloadInfo? = activeDownloads[productId]

    fun getDownloadInfoByAppId(appId: Int): DownloadInfo? =
        gameByAppId(appId)?.let { activeDownloads[it.productId] }

    fun getActiveDownloads(): Map<String, DownloadInfo> = HashMap(activeDownloads)

    /** Product IDs of downloads that stopped before completing and can be resumed. */
    fun getPartialDownloads(): List<String> =
        games().filter { !it.isInstalled }.map { it.productId }.filter(::hasPartialDownload)

    /**
     * Where a product is installed, following GameNative's per-store layout:
     * `<dataDir>/Xbox/<productId>`, or `<external>/Xbox/games/<productId>` when
     * external storage is enabled.
     */
    fun installDirFor(productId: String): File {
        val context = requireNotNull(appContext)
        val external = app.gamenative.PrefManager.externalStoragePath
        val root = if (app.gamenative.PrefManager.useExternalStorage && File(external).exists()) {
            File(external, "Xbox/games")
        } else {
            File(context.dataDir, "Xbox")
        }
        return File(root, productId)
    }

    /**
     * Which PC packages the Store offers for [productId] (network call). READY names the one
     * to install; CHOICE asks the user to pick a supported one; UNAVAILABLE has none.
     */
    fun resolvePackage(productId: String): XodusProtocol.PackagePlan = backend.resolvePackage(productId, market)

    /** A download was started earlier and stopped before completing (paused, cancelled or failed). */
    fun hasPartialDownload(productId: String): Boolean {
        val game = gameByProductId(productId) ?: return false
        if (game.isInstalled || activeDownloads.containsKey(productId)) return false
        return game.installPath?.let { XboxDownload.hasPartialDownload(File(it)) } == true
    }

    /**
     * Starts (or resumes) a package download through GameNative's download queue, as the
     * other stores do. [contentId] defaults to the package chosen when this product's
     * download first started, so the queue can resume it without asking again. Xodus keeps
     * a checkpoint journal in [installPath], so a cancelled download resumes where it stopped.
     */
    fun downloadGame(
        context: Context,
        productId: String,
        contentId: String? = null,
        installPath: String? = null,
    ): Result<DownloadInfo> {
        init(context)
        activeDownloads[productId]?.let { return Result.success(it) }
        val game = gameByProductId(productId) ?: return Result.failure(XodusException("Unknown Xbox game."))
        val chosen = contentId ?: game.contentId
            ?: return Result.failure(XodusException("Choose which package to install first."))
        val installDir = File(installPath ?: game.installPath ?: installDirFor(productId).path)
        // Remember the choice and location so a queued resume can start it again.
        library.update(productId) { it.copy(contentId = chosen, installPath = installDir.absolutePath) }

        val downloadInfo = DownloadInfo(jobCount = 1, gameId = game.appId, downloadingAppIds = CopyOnWriteArrayList())
        downloadInfo.setActive(true)
        downloadInfo.updateStatusMessage(context.getString(R.string.download_preparing))
        activeDownloads[productId] = downloadInfo
        PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(game.appId, true))
        GameDownloadService.registerDownload(
            gameSource = GameSource.XBOX,
            gameId = productId,
            downloadInfo = downloadInfo,
            installPath = installDir.absolutePath,
        )

        val download = XboxDownload(backend, productId, chosen, installDir, market)
        val job = scope.launch {
            // The queue pauses a download by cancelling its job: stop the process, keep the files.
            coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) download.cancel() }
            var reported = -1L
            val outcome = try {
                download.run { progress ->
                    progress.totalBytes?.let { downloadInfo.setTotalExpectedBytes(it) }
                    if (reported < 0) {
                        downloadInfo.initializeBytesDownloaded(progress.completedBytes)
                    } else {
                        downloadInfo.updateBytesDownloaded(progress.completedBytes - reported)
                    }
                    reported = progress.completedBytes
                    downloadInfo.updateStatusMessage(progress.phase.label)
                    downloadInfo.emitProgressChange()
                }
            } catch (e: Exception) {
                XboxDownload.Outcome.Failed(e.message ?: "The download failed.")
            }
            try {
                when (outcome) {
                    XboxDownload.Outcome.Complete -> {
                        library.markInstalled(productId, installDir, chosen)
                        downloadInfo.setActive(false)
                        SnackbarManager.show(context.getString(R.string.xbox_download_complete, game.title))
                        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.XBOX))
                        GameDownloadService.unregisterDownload(context, GameSource.XBOX, productId)
                    }
                    // Paused by the queue or cancelled by the user; files stay for a resume.
                    XboxDownload.Outcome.Cancelled -> downloadInfo.setActive(false)
                    is XboxDownload.Outcome.Failed -> {
                        Timber.tag(TAG).w("Download failed for $productId: ${outcome.message}")
                        downloadInfo.setActive(false)
                        SnackbarManager.show(context.getString(R.string.xbox_download_failed, outcome.message))
                    }
                }
            } finally {
                activeDownloads.remove(productId, downloadInfo)
                PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(game.appId, false))
                notifyChanged()
            }
        }
        downloadInfo.setDownloadJob(job)
        notifyChanged()
        return Result.success(downloadInfo)
    }

    /** Stops an active download; its files stay so it can be resumed. */
    fun cancelDownload(productId: String): Boolean {
        val info = activeDownloads[productId] ?: return false
        info.cancel()
        return true
    }

    /**
     * Removes the game's files, its launch view and its container, and drops it from the
     * download queue, as AmazonService.deleteGame does.
     */
    suspend fun deleteGame(context: Context, productId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            init(context)
            val game = gameByProductId(productId) ?: throw XodusException("Unknown Xbox game.")
            GameDownloadService.removeDownload(context, GameSource.XBOX, productId)
            activeDownloads[productId]?.let { info ->
                info.cancel()
                info.awaitCompletion()
            }
            game.installPath?.let { File(it).deleteRecursively() }
            paths.viewRoot(productId).deleteRecursively()
            library.markUninstalled(productId)
            ContainerUtils.deleteContainer(context, game.containerId)
            PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.XBOX))
            notifyChanged()
        }
    }

    // ------------------------------------------------------------------ launch

    /** What XServerScreen needs to start an Xbox game. */
    data class LaunchPlan(
        /** Host directory to map to [drive]. */
        val driveRoot: File,
        val drive: Char,
        /** Windows path of the executable, e.g. `A:\xodus-view-1\Game.exe`. */
        val windowsExecutable: String,
        val workingDirectory: File,
        /** Variables for the game; [applyTo] adds the overlay's DLL overrides to them. */
        val environment: Map<String, String>,
        val overlayDlls: List<XboxRuntimeOverlay.Dll>,
        /** The decrypted executable imports d3d12.dll (it needs the VKD3D wrapper). */
        val usesD3D12: Boolean,
    ) {
        /**
         * Adds the plan's variables to [envVars]. The overlay's overrides are merged into
         * WINEDLLOVERRIDES as it is now, so a plan prepared before the container's own
         * DLL setup keeps the overrides that setup added.
         */
        fun applyTo(envVars: EnvVars) {
            for ((name, value) in environment) envVars.put(name, value)
            envVars.put("WINEDLLOVERRIDES", XboxRuntimeOverlay.mergeDllOverrides(envVars.get("WINEDLLOVERRIDES"), overlayDlls))
        }
    }

    @Volatile private var prepared: Pair<Int, LaunchPlan>? = null

    /**
     * Prepares a launch before the container's DLLs are set up, so the wrapper can be chosen
     * from the decrypted executable ([applyGraphicsDefaults]); [takePreparedLaunch] then
     * hands the plan to the launch command.
     */
    fun prepareLaunchEarly(appId: Int, container: Container): LaunchPlan =
        prepareLaunch(appId, container).also { prepared = appId to it }

    fun takePreparedLaunch(appId: Int): LaunchPlan? = prepared?.takeIf { it.first == appId }?.second.also { prepared = null }

    /**
     * Records the automatic DX wrapper choice for this game's container (once) and returns
     * the wrapper to use for this launch when it changed, or null.
     */
    fun applyGraphicsDefaults(container: Container, plan: LaunchPlan, defaultDxWrapper: String): String? {
        val decided = container.getExtra(XboxGraphics.AUTO_DX_WRAPPER_EXTRA).takeIf { it.isNotEmpty() }
        if (decided != null) return null
        val wrapper = XboxGraphics.autoDxWrapper(decided, container.dxWrapper, defaultDxWrapper, plan.usesD3D12)
        if (wrapper != null) container.dxWrapper = wrapper
        container.putExtra(XboxGraphics.AUTO_DX_WRAPPER_EXTRA, wrapper ?: "kept")
        container.saveData()
        Timber.tag(TAG).i("DX wrapper for ${container.id}: ${wrapper ?: "kept ${container.dxWrapper}"} (d3d12 import: ${plan.usesD3D12})")
        return wrapper
    }

    private class ActiveLaunch(val launch: XboxLaunch, val service: XodusServiceSession?)
    @Volatile private var active: ActiveLaunch? = null

    /**
     * Prepares everything an Xbox game needs before Wine starts: runtime DLLs and
     * registrations in the prefix, the account service with its loopback bridge,
     * and the decrypted launch view. Blocks (license + decryption); call off the UI thread.
     */
    fun prepareLaunch(appId: Int, container: Container): LaunchPlan {
        finishLaunch()
        val game = library.byAppId(appId) ?: throw XodusException("Unknown Xbox game.")
        val installDir = game.installPath?.let(::File)?.takeIf { XboxDownload.isInstalled(it) }
            ?: throw XodusException("${game.title} is not installed.")
        XboxCaBundle.ensure(paths.caBundle)
        val prefix = File(container.rootDir, ".wine")
        val overlayDlls = installRuntime(prefix)

        val service = runCatching {
            XodusServiceSession.start(backend, paths, AndroidUnixConnector, signals = AndroidProcessSignals)
        }.onFailure {
            // Games can still start without it; Xbox sign-in, Store and cloud saves will not work.
            Timber.tag(TAG).w(it, "Xbox account service unavailable")
        }.getOrNull()

        val launch = try {
            XboxLaunch.prepare(backend, installDir, game.executable, market, paths.viewRoot(game.productId))
        } catch (e: Exception) {
            service?.close()
            throw e
        }
        active = ActiveLaunch(launch, service)

        val env = LinkedHashMap<String, String>()
        env.putAll(launch.ready.environment)
        service?.let { env.putAll(it.gameEnvironment) }
        if (service == null) env["XODUS_SAVE_SYNC"] = "none"
        val windowsExecutable = launch.windowsExecutable(XboxConstants.LAUNCH_DRIVE)
        // A:\<view>\Game.exe lives at <viewRoot>/<view>/Game.exe on the host.
        val hostExecutable = File(launch.viewRoot, windowsExecutable.substringAfter(':').trimStart('\\').replace('\\', '/'))
        return LaunchPlan(
            driveRoot = launch.viewRoot,
            drive = XboxConstants.LAUNCH_DRIVE,
            windowsExecutable = windowsExecutable,
            workingDirectory = launch.workingDirectory,
            environment = env,
            overlayDlls = overlayDlls,
            usesD3D12 = XboxGraphics.importsD3D12(hostExecutable),
        )
    }

    /** Called when the game's Wine session ends: removes the decrypted view, then stops the service. */
    fun finishLaunch() {
        val current = active ?: return
        active = null
        runCatching { current.launch.close() }.onFailure { Timber.tag(TAG).w(it, "launch view cleanup failed") }
        // Give the runtime a moment to finish cloud-save uploads it started at exit.
        executor.execute {
            Thread.sleep(3000)
            runCatching { current.service?.close() }
        }
    }

    /** Unpacks the bundled overlay (once per APK) and installs it into [prefix]. */
    fun installRuntime(prefix: File): List<XboxRuntimeOverlay.Dll> {
        val context = requireNotNull(appContext)
        val staging = File(paths.dataDir, "runtime-overlay")
        val stamp = File(staging, ".apk-version")
        val apkVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName}-${it.lastUpdateTime}" }
        }.getOrDefault("unknown")
        val overlay = XboxRuntimeOverlay(staging)
        if (!overlay.isStaged || !stamp.isFile || stamp.readText() != apkVersion) {
            staging.deleteRecursively()
            staging.mkdirs()
            // The extractor only creates directories for explicit tar entries, so make each
            // file's parent here; overlays built before directory entries were added lack them.
            val extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, XboxConstants.RUNTIME_OVERLAY_ASSET, staging) { file, _ ->
                file.parentFile?.mkdirs()
                file
            }
            check(extracted) { "The Xbox runtime could not be unpacked." }
            stamp.writeText(apkVersion)
        }
        val dlls = overlay.installInto(prefix)
        WineRegistryEditor(File(prefix, "system.reg")).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            for ((key, dllPath) in XboxRuntimeOverlay.activatableClassRegistry()) {
                if (editor.getStringValue(key, "DllPath") != dllPath) editor.setStringValue(key, "DllPath", dllPath)
            }
        }
        return dlls
    }

    // ------------------------------------------------------------------ listeners

    fun interface Listener {
        fun onXboxChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)
    private fun notifyChanged() = listeners.forEach { runCatching { it.onXboxChanged() } }

    fun runInBackground(task: () -> Unit) = executor.execute(task)
}
