package app.gamenative.service.xbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Public Microsoft Store catalog metadata (titles, art, PC availability).
 * Display data only: ownership always comes from the backend's entitlement check.
 */
object XboxCatalog {
    data class Product(
        val productId: String,
        val title: String,
        val publisher: String?,
        val description: String?,
        val boxArt: String?,
        val hero: String?,
        val screenshots: List<String>,
        /** Product type as the Store reports it, e.g. "Game", "Durable" (add-on), "Pass". */
        val productType: String?,
        /** True when the catalog lists a Windows.Desktop package or a PC-capable bundle. */
        val hasPcPackage: Boolean,
        val isPlayAnywhere: Boolean,
        val xboxTitleId: String?,
    )

    private const val MAX_IDS_PER_REQUEST = 20

    fun url(productIds: List<String>, market: String, language: String): String {
        val ids = productIds.joinToString(",")
        return "${XboxConstants.DISPLAY_CATALOG_URL}?bigIds=${URLEncoder.encode(ids, "UTF-8")}" +
            "&market=${URLEncoder.encode(market, "UTF-8")}&languages=${URLEncoder.encode(language, "UTF-8")}"
    }

    /** Fetches metadata for [productIds] in batches; products the catalog does not know are omitted. */
    fun fetch(productIds: Collection<String>, market: String, language: String = "en-us,neutral"): Map<String, Product> {
        val result = LinkedHashMap<String, Product>()
        productIds.filter(XodusProtocol::isProductId).distinct().chunked(MAX_IDS_PER_REQUEST).forEach { batch ->
            val connection = URL(url(batch, market, language)).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode != 200) return@forEach
                val body = connection.inputStream.use { it.readBytes() }
                if (body.size > 16_000_000) return@forEach
                parse(String(body, Charsets.UTF_8)).forEach { result[it.productId] = it }
            } finally {
                connection.disconnect()
            }
        }
        return result
    }

    fun parse(json: String): List<Product> {
        val products = JSONObject(json).optJSONArray("Products") ?: return emptyList()
        val parsed = ArrayList<Product>()
        for (i in 0 until products.length()) {
            val product = products.optJSONObject(i) ?: continue
            val id = product.optString("ProductId")
            if (!XodusProtocol.isProductId(id)) continue
            val localized = product.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: continue
            val title = localized.optString("ProductTitle").trim().take(512).takeIf { it.isNotEmpty() } ?: continue
            val images = localized.optJSONArray("Images") ?: JSONArray()
            fun image(vararg purposes: String): String? {
                for (purpose in purposes) {
                    for (j in 0 until images.length()) {
                        val item = images.optJSONObject(j) ?: continue
                        if (item.optString("ImagePurpose") == purpose) return normalizeImage(item.optString("Uri"))
                    }
                }
                return null
            }
            val screenshots = (0 until images.length()).mapNotNull { j ->
                images.optJSONObject(j)?.takeIf { it.optString("ImagePurpose") == "Screenshot" }?.let { normalizeImage(it.optString("Uri")) }
            }.distinct().take(12)
            val properties = product.optJSONObject("Properties")
            val attributes = properties?.optJSONArray("Attributes")
            val playAnywhere = attributes != null && (0 until attributes.length()).any {
                attributes.optJSONObject(it)?.optString("Name").equals("XPA", ignoreCase = true)
            }
            val titleId = product.optJSONArray("AlternateIds")?.let { ids ->
                (0 until ids.length()).mapNotNull { ids.optJSONObject(it) }
                    .firstOrNull { it.optString("IdType").equals("XboxTitleId", ignoreCase = true) }
                    ?.optString("Value")?.takeIf { v -> v.isNotBlank() }
            }
            parsed += Product(
                productId = id,
                title = title,
                publisher = localized.optString("PublisherName").takeIf { it.isNotBlank() },
                description = localized.optString("ShortDescription").ifBlank { localized.optString("ProductDescription") }.takeIf { it.isNotBlank() }?.take(4000),
                boxArt = image("BoxArt", "Poster", "Tile"),
                hero = image("SuperHeroArt", "TitledHeroArt", "Hero"),
                screenshots = screenshots,
                productType = product.optString("ProductType").takeIf { it.isNotBlank() },
                hasPcPackage = hasDesktopPackage(product),
                isPlayAnywhere = playAnywhere,
                xboxTitleId = titleId,
            )
        }
        return parsed
    }

    private fun hasDesktopPackage(product: JSONObject): Boolean {
        val skus = product.optJSONArray("DisplaySkuAvailabilities") ?: return false
        for (i in 0 until skus.length()) {
            val packages = skus.optJSONObject(i)?.optJSONObject("Sku")?.optJSONObject("Properties")?.optJSONArray("Packages") ?: continue
            for (j in 0 until packages.length()) {
                val pkg = packages.optJSONObject(j) ?: continue
                val platforms = pkg.optJSONArray("PlatformDependencies") ?: continue
                for (k in 0 until platforms.length()) {
                    if (platforms.optJSONObject(k)?.optString("PlatformName") == "Windows.Desktop") return true
                }
            }
            // Bundles list their PC member in BundledSkus instead of Packages.
            if (skus.optJSONObject(i)?.optJSONObject("Sku")?.optJSONObject("Properties")?.optJSONArray("BundledSkus")?.length() ?: 0 > 0) return true
        }
        return false
    }

    /** Catalog image URIs are protocol-relative (`//store-images…`). Only HTTPS is allowed through. */
    fun normalizeImage(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val full = if (uri.startsWith("//")) "https:$uri" else uri
        return full.takeIf { it.startsWith("https://") && it.length < 2048 }
    }
}
