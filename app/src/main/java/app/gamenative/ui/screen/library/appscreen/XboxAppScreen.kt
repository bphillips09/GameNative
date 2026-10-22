package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.XboxGame
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import app.gamenative.service.xbox.XboxService
import app.gamenative.service.xbox.XodusProtocol
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import com.winlator.core.StringUtils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Xbox (Microsoft Store PC) [BaseAppScreen] implementation, following [AmazonAppScreen]. */
class XboxAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "XboxAppScreen"

        private val uninstallDialogAppIds = mutableStateListOf<String>()

        /** Install confirmation: the supported packages, with the first preselected. */
        data class XboxInstallDialogData(
            val choices: List<XodusProtocol.PackageChoice>,
            val availableSpace: String,
        )

        private val installDialogDataMap = mutableStateMapOf<String, XboxInstallDialogData>()

        var showDeletingDialog by mutableStateOf(false)

        fun gameOf(libraryItem: LibraryItem): XboxGame? = XboxService.gameByAppId(libraryItem.gameId)

        fun productIdOf(libraryItem: LibraryItem): String = gameOf(libraryItem)?.productId ?: ""
    }

    // ── BaseAppScreen contract ─────────────────────────────────────────────

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        var game by remember(libraryItem.gameId) { mutableStateOf<XboxGame?>(null) }
        // Re-read from the database when install state changes (path and size update then).
        var refreshKey by remember(libraryItem.gameId) { mutableStateOf(0) }
        DisposableEffect(libraryItem.gameId) {
            val listener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == libraryItem.gameId) refreshKey++
            }
            PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            onDispose { PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener) }
        }
        LaunchedEffect(libraryItem.gameId, refreshKey) {
            game = withContext(Dispatchers.IO) { gameOf(libraryItem) }
        }

        val g = game
        val iconUrl = g?.boxArt?.takeIf { it.isNotEmpty() } ?: libraryItem.iconHash.takeIf { it.isNotEmpty() }
        val heroImageUrl = g?.hero?.takeIf { it.isNotEmpty() } ?: iconUrl
        return GameDisplayInfo(
            name = g?.title ?: libraryItem.name,
            developer = g?.publisher.orEmpty(),
            releaseDate = 0L,
            heroImageUrl = heroImageUrl,
            iconUrl = iconUrl,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = g?.takeIf { it.isInstalled }?.installPath,
            sizeOnDisk = g?.takeIf { it.isInstalled }?.sizeBytes?.takeIf { it > 0 }?.let(StringUtils::formatBytes),
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean =
        gameOf(libraryItem)?.isInstalled == true

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val game = gameOf(libraryItem) ?: return false
        return !game.isInstalled && game.access != null && XboxService.getDownloadInfo(game.productId) == null
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean =
        XboxService.getDownloadInfoByAppId(libraryItem.gameId) != null

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float =
        XboxService.getDownloadInfoByAppId(libraryItem.gameId)?.getProgress() ?: 0f

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean =
        XboxService.hasPartialDownload(productIdOf(libraryItem))

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        if (isDownloading(context, libraryItem)) return
        if (hasPartialDownload(context, libraryItem)) {
            // Resume with the package chosen when the download started.
            performDownload(context, libraryItem, contentId = null)
            return
        }
        if (isInstalled(context, libraryItem)) {
            onClickPlay(false)
            return
        }
        val productId = productIdOf(libraryItem)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val plan = XboxService.resolvePackage(productId)
                val choices = plan.supportedChoices
                if (plan.status == XodusProtocol.PlanStatus.UNAVAILABLE || choices.isEmpty()) {
                    SnackbarManager.show(context.getString(R.string.xbox_install_unavailable))
                    return@launch
                }
                val installRoot = XboxService.installDirFor(productId).parentFile ?: context.dataDir
                val available = StorageUtils.getAvailableSpaceForUncreatedPath(installRoot.path)
                withContext(Dispatchers.Main) {
                    installDialogDataMap[libraryItem.appId] = XboxInstallDialogData(
                        // READY plans name the package; CHOICE plans let the user pick.
                        choices = plan.automaticContentId?.let { id -> choices.filter { it.contentId == id } } ?: choices,
                        availableSpace = StorageUtils.formatBinarySize(available),
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Could not resolve the package for $productId")
                SnackbarManager.show(context.getString(R.string.xbox_install_start_failed, e.message ?: "Unknown error"))
            }
        }
    }

    private fun performDownload(context: Context, libraryItem: LibraryItem, contentId: String?) {
        val productId = productIdOf(libraryItem)
        CoroutineScope(Dispatchers.IO).launch {
            val result = XboxService.downloadGame(context, productId, contentId)
            result.exceptionOrNull()?.let { e ->
                Timber.tag(TAG).e(e, "downloadGame failed for $productId")
                SnackbarManager.show(context.getString(R.string.xbox_install_start_failed, e.message ?: "Unknown error"))
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val productId = productIdOf(libraryItem)
        if (XboxService.getDownloadInfo(productId) != null) {
            XboxService.cancelDownload(productId)
        } else {
            performDownload(context, libraryItem, contentId = null)
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        if (isDownloading(context, libraryItem) || hasPartialDownload(context, libraryItem)) {
            showInstallDialog(
                libraryItem.appId,
                MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.epic_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                ),
            )
        } else if (isInstalled(context, libraryItem)) {
            if (!uninstallDialogAppIds.contains(libraryItem.appId)) uninstallDialogAppIds.add(libraryItem.appId)
        }
    }

    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        val productId = productIdOf(libraryItem)
        showDeletingDialog = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val gameRootDir = getInstallPath(context, libraryItem)?.let(::File)
                val result = XboxService.deleteGame(context, productId)
                DownloadService.invalidateCache()
                if (result.isSuccess) {
                    cleanupNexusModsForApp(context, libraryItem, gameRootDir)
                } else {
                    Timber.tag(TAG).e(result.exceptionOrNull(), "Uninstall failed for $productId")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { showDeletingDialog = false }
            }
        }
    }

    /** Store updates are not tracked for Xbox games yet. */
    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) = Unit

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? =
        gameOf(libraryItem)?.takeIf { it.isInstalled }?.installPath

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        val gameId = libraryItem.gameId
        val productId = productIdOf(libraryItem)
        var currentDownloadInfo: DownloadInfo? = null
        val progressListener: (Float) -> Unit = { progress -> onProgressChanged(progress) }

        fun attach(info: DownloadInfo?) {
            currentDownloadInfo?.removeProgressListener(progressListener)
            currentDownloadInfo = info
            info?.let {
                it.addProgressListener(progressListener)
                onProgressChanged(it.getProgress())
            }
        }
        attach(XboxService.getDownloadInfo(productId))

        val downloadStatusListener: (AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            if (event.appId == gameId) {
                if (event.isDownloading) {
                    attach(XboxService.getDownloadInfo(productId))
                } else {
                    attach(null)
                    // Not installed after the download stopped: paused or cancelled, so offer Resume.
                    onHasPartialDownloadChanged?.invoke(XboxService.hasPartialDownload(productId))
                }
                onStateChanged()
            }
        }
        val installListener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            if (event.appId == gameId) onStateChanged()
        }
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        return {
            attach(null)
            PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
            PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }
    }

    override fun getExportFileExtension(): String = ".xbox"

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        var showDialog by remember { mutableStateOf(false) }
        if (showDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showDialog = false },
            )
        }
        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = { showDialog = true },
        )
    }

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val appId = libraryItem.appId
        val productId = productIdOf(libraryItem)

        // ── Cancel-download / delete-partial confirmation ──
        var installDialogState by remember(appId) {
            mutableStateOf(BaseAppScreen.getInstallDialogState(appId) ?: MessageDialogState(false))
        }
        LaunchedEffect(appId) {
            snapshotFlow { BaseAppScreen.getInstallDialogState(appId) }
                .collect { installDialogState = it ?: MessageDialogState(false) }
        }
        if (installDialogState.visible) {
            val onConfirmClick: (() -> Unit)? = when (installDialogState.type) {
                DialogType.CANCEL_APP_DOWNLOAD -> {
                    {
                        BaseAppScreen.hideInstallDialog(appId)
                        showDeletingDialog = true
                        scope.launch {
                            try {
                                XboxService.deleteGame(context, productId)
                                DownloadService.invalidateCache()
                                withContext(Dispatchers.Main) {
                                    PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(libraryItem.gameId, false))
                                    PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId, GameSource.XBOX))
                                }
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main) { showDeletingDialog = false }
                            }
                        }
                    }
                }
                else -> null
            }
            MessageDialog(
                visible = installDialogState.visible,
                onDismissRequest = { BaseAppScreen.hideInstallDialog(appId) },
                onConfirmClick = onConfirmClick,
                onDismissClick = { BaseAppScreen.hideInstallDialog(appId) },
                confirmBtnText = installDialogState.confirmBtnText,
                dismissBtnText = installDialogState.dismissBtnText,
                title = installDialogState.title,
                message = installDialogState.message,
            )
        }

        // ── Install confirmation, with a package choice when the Store offers several ──
        val installData = installDialogDataMap[appId]
        if (installData != null) {
            var selected by remember(appId, installData) { mutableStateOf(installData.choices.first().contentId) }
            AlertDialog(
                onDismissRequest = { installDialogDataMap.remove(appId) },
                title = { Text(stringResource(R.string.xbox_install_title, libraryItem.name)) },
                text = {
                    Column {
                        if (installData.choices.size > 1) {
                            Text(stringResource(R.string.xbox_install_choose_package))
                            Spacer(Modifier.height(8.dp))
                            installData.choices.forEach { choice ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = choice.contentId },
                                ) {
                                    RadioButton(selected = selected == choice.contentId, onClick = { selected = choice.contentId })
                                    Column(Modifier.padding(start = 4.dp)) {
                                        Text(choice.title)
                                        Text(
                                            "${choice.kind} · ${choice.packageFormat}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(stringResource(R.string.xbox_install_available_space, installData.availableSpace))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        installDialogDataMap.remove(appId)
                        performDownload(context, libraryItem, selected)
                    }) { Text(stringResource(R.string.install)) }
                },
                dismissButton = {
                    TextButton(onClick = { installDialogDataMap.remove(appId) }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        if (showDeletingDialog) {
            LoadingDialog(visible = true, progress = -1f, message = stringResource(R.string.deleting))
        }

        // ── Uninstall confirmation ──
        if (uninstallDialogAppIds.contains(appId)) {
            AlertDialog(
                onDismissRequest = { uninstallDialogAppIds.remove(appId) },
                title = { Text(stringResource(R.string.amazon_uninstall_game_title)) },
                text = { Text(stringResource(R.string.amazon_uninstall_confirmation_message, libraryItem.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        uninstallDialogAppIds.remove(appId)
                        performUninstall(context, libraryItem)
                    }) { Text(stringResource(R.string.uninstall)) }
                },
                dismissButton = {
                    TextButton(onClick = { uninstallDialogAppIds.remove(appId) }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true
}
