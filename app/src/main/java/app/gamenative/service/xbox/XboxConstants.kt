package app.gamenative.service.xbox

object XboxConstants {
    /** aarch64-linux-android executables built by tools/build-xodus.sh. */
    const val CLI_BINARY = "libxodus_cli.so"
    const val SERVICE_BINARY = "libxodus_service.so"

    /** Windows-side runtime overlay (xgameruntime.dll and friends) built by tools/build-xodus-runtime.sh. */
    const val RUNTIME_OVERLAY_ASSET = "xbox/xodus-runtime-overlay.tzst"

    const val DEFAULT_MARKET = "US"

    /** Drive letter the launch view is mapped to inside the Wine prefix. */
    const val LAUNCH_DRIVE = 'A'

    /** Sign-in WebView user agent: the page only offers the token-broker flow to this host. */
    const val SIGN_IN_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; MSAppHost/3.0) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/70.0.3538.102 Safari/537.36 Edge/18.26100"

    /** Public Store metadata (titles, art); never used for ownership. */
    const val DISPLAY_CATALOG_URL = "https://displaycatalog.mp.microsoft.com/v7.0/products"
}
