package com.mmhw.csvtv

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView ad blocker for TV.
 *
 * How users extend blocking (no file editing):
 * - Settings → Adblock → **My blocked sites** (add/remove domains on-device)
 * - Settings → Adblock → **List packs** (toggle trusted remote packs; auto-download)
 * - Browser: long-press adblock button → block current site
 *
 * Bundled assets (`assets/adblock/`) and remote host files are app internals only.
 */
data class AdBlockSource(
    val id: String,
    val name: String,
    val type: String, // "asset" | "remote"
    val path: String? = null,
    val url: String? = null,
    val enabled: Boolean = true,
    val lastUpdatedMs: Long = 0L,
    val ruleCount: Int = 0,
    val lastError: String? = null,
    val isCustom: Boolean = false
) {
    val isRemote: Boolean get() = type == "remote"
    val isAsset: Boolean get() = type == "asset"
}

object AdBlocker {
    private const val TAG = "AdBlocker"
    private const val PREFS = "AppPrefs"
    private const val KEY_SOURCES = "adblock_sources_json"
    private const val KEY_USER_HOSTS = "adblock_user_hosts_json"
    private const val KEY_OVERLAY_BLOCK = "adblock_overlay_block"
    private const val KEY_LAST_AUTO_UPDATE = "adblock_last_auto_update_ms"
    private const val SOURCES_ASSET = "adblock/sources.json"
    private const val COSMETIC_ASSET = "adblock/cosmetic_selectors.txt"
    private const val CACHE_DIR = "adblock"
    private const val DEFAULT_AUTO_UPDATE_DAYS = 7L
    private const val MAX_USER_HOSTS = 500

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile
    private var hosts: Set<String> = emptySet()

    @Volatile
    private var urlContains: Array<String> = emptyArray()

    /** CSS selectors for cosmetic hiding (loaded from assets). */
    @Volatile
    private var cosmeticSelectors: Array<String> = emptyArray()

    @Volatile
    private var loaded = false

    private val updateInProgress = AtomicBoolean(false)

    private val lock = Any()

    // region Matching

    fun shouldBlock(url: String): Boolean {
        if (!loaded) return false
        if (url.isEmpty()) return false

        val lower = url.lowercase()
        // Skip non-network schemes
        if (lower.startsWith("data:") ||
            lower.startsWith("blob:") ||
            lower.startsWith("about:") ||
            lower.startsWith("javascript:")
        ) {
            return false
        }

        val host = extractHost(url) ?: extractHost(lower)
        if (host != null && hostMatches(host)) {
            return true
        }

        val pathRules = urlContains
        if (pathRules.isNotEmpty()) {
            for (fragment in pathRules) {
                if (lower.contains(fragment)) return true
            }
        }
        return false
    }

    fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun ruleStats(): Pair<Int, Int> = hosts.size to urlContains.size

    private fun hostMatches(host: String): Boolean {
        val set = hosts
        if (set.isEmpty()) return false
        var h = host
        while (h.isNotEmpty()) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
        return false
    }

    /**
     * Extract host without Android Uri (works in JVM unit tests).
     * Handles scheme-relative and missing-scheme URLs.
     */
    private fun extractHost(url: String): String? {
        return try {
            var candidate = url.trim()
            if (candidate.startsWith("//")) {
                candidate = "http:$candidate"
            } else if (!candidate.contains("://")) {
                // bare host or host/path
                candidate = "http://$candidate"
            }
            val uri = URI(candidate)
            var host = uri.host?.lowercase()?.trim('.') ?: return null
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            if (host.isEmpty()) null else host
        } catch (_: Exception) {
            // Fallback: rough parse between scheme and first '/' or '?'
            try {
                var s = url.lowercase()
                val schemeIdx = s.indexOf("://")
                if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
                if (s.startsWith("//")) s = s.substring(2)
                val end = s.indexOfAny(charArrayOf('/', '?', '#', ':')).let { if (it >= 0) it else s.length }
                val host = s.substring(0, end).trim('.')
                host.takeIf { it.contains('.') }
            } catch (_: Exception) {
                null
            }
        }
    }

    // endregion

    // region Load / reload

    /**
     * Ensure rules are loaded. Safe to call from any thread; triggers background
     * auto-update of remote sources when the interval has elapsed.
     */
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        if (!loaded) {
            reload(app)
        }
        maybeAutoUpdate(app)
    }

    fun reload(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val hostSet = HashSet<String>(4096)
            val pathList = ArrayList<String>(64)
            val sources = getSources(app)
            for (source in sources) {
                if (!source.enabled) continue
                try {
                    val text = readSourceText(app, source) ?: continue
                    parseRulesInto(text, hostSet, pathList)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load source ${source.id}: ${e.message}")
                }
            }
            // User-managed domains (Settings / browser) — always applied
            for (userHost in getUserHosts(app)) {
                hostSet.add(userHost)
            }

            hosts = hostSet
            urlContains = pathList.map { it.lowercase() }.distinct().toTypedArray()
            cosmeticSelectors = loadCosmeticSelectors(app)
            loaded = true
            Log.i(
                TAG,
                "Loaded ${hosts.size} hosts, ${urlContains.size} path rules, " +
                    "${cosmeticSelectors.size} cosmetic selectors " +
                    "(${sources.count { it.enabled }} packs, ${getUserHosts(app).size} user)"
            )
        }
    }

    private fun loadCosmeticSelectors(context: Context): Array<String> {
        return try {
            context.assets.open(COSMETIC_ASSET).bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .distinct()
                    .toList()
                    .toTypedArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No cosmetic selectors asset", e)
            emptyArray()
        }
    }

    // region In-page popup / overlay blocking (JS + CSS)

    /** Default ON — hide spam overlays and known ad DOM. */
    fun isOverlayBlockingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_BLOCK, true)
    }

    fun setOverlayBlockingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_BLOCK, enabled)
            .apply()
    }

    /**
     * JavaScript injected into pages when network adblock is on and overlay blocking is enabled.
     * - Injects CSS for [cosmetic_selectors.txt]
     * - Removes large fixed high-z-index spam overlays (heuristic)
     * - Unlocks body scroll often frozen by interstitials
     * - Watches DOM mutations + light interval for delayed popups
     */
    fun buildOverlayCleanerScript(): String {
        val selectors = cosmeticSelectors
        val cssBody = if (selectors.isEmpty()) {
            ""
        } else {
            selectors.joinToString(",") +
                "{display:none!important;visibility:hidden!important;pointer-events:none!important;" +
                "height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;}"
        }
        // Safe embedding of CSS text into JS source
        val cssJsLiteral = JSONObject.quote(cssBody)

        // Keep script self-contained; re-entry safe via window.__csvtvAbInstalled
        return """
            (function(){
              try {
                if (window.__csvtvAbInstalled) {
                  if (window.__csvtvAbSweep) window.__csvtvAbSweep();
                  return;
                }
                window.__csvtvAbInstalled = true;

                var CSS = $cssJsLiteral;
                function ensureStyle() {
                  var s = document.getElementById('csvtv-ab-css');
                  if (!s) {
                    s = document.createElement('style');
                    s.id = 'csvtv-ab-css';
                    s.type = 'text/css';
                    (document.head || document.documentElement).appendChild(s);
                  }
                  if (CSS && s.textContent !== CSS) s.textContent = CSS;
                }

                function hasMedia(el) {
                  try {
                    if (!el || !el.querySelector) return false;
                    if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') return true;
                    return !!el.querySelector('video,audio');
                  } catch(e) { return true; }
                }

                function spamName(el) {
                  try {
                    var id = (el.id || '') + '';
                    var cls = (typeof el.className === 'string') ? el.className : (el.className && el.className.baseVal) || '';
                    var t = (id + ' ' + cls).toLowerCase();
                    return /(^|[^a-z])(ads?|advert|sponsor|popup|pop-up|interstitial|overlay-ad|adoverlay|adsbox|taboola|outbrain|exit[-_]?intent|smartbanner)([^a-z]|$)/.test(t);
                  } catch(e) { return false; }
                }

                function looksLikeSpamOverlay(el) {
                  if (!el || el === document.body || el === document.documentElement) return false;
                  if (el.id === 'csvtv-ab-css') return false;
                  if (hasMedia(el)) return false;
                  var st;
                  try { st = window.getComputedStyle(el); } catch(e) { return false; }
                  if (!st || st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                  var pos = st.position;
                  if (pos !== 'fixed' && pos !== 'sticky') {
                    // non-fixed: only kill clear spam-named nodes that are large
                    if (!spamName(el)) return false;
                  }
                  var rect;
                  try { rect = el.getBoundingClientRect(); } catch(e) { return false; }
                  var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
                  if (rect.width < 40 || rect.height < 40) return false;
                  var cover = (rect.width * rect.height) / (vw * vh);
                  var z = parseInt(st.zIndex, 10);
                  if (isNaN(z)) z = 0;
                  // Large fixed overlay with high z-index (classic interstitial)
                  if ((pos === 'fixed' || pos === 'sticky') && cover >= 0.45 && z >= 100) return true;
                  if ((pos === 'fixed' || pos === 'sticky') && cover >= 0.25 && z >= 1000) return true;
                  if ((pos === 'fixed' || pos === 'sticky') && cover >= 0.15 && z >= 9999) return true;
                  // Named spam nodes that cover a decent area
                  if (spamName(el) && cover >= 0.2) return true;
                  if (spamName(el) && (pos === 'fixed' || pos === 'sticky') && cover >= 0.08 && z >= 10) return true;
                  return false;
                }

                function unlockScroll() {
                  try {
                    var de = document.documentElement, b = document.body;
                    if (!de || !b) return;
                    // Only clear overflow lock if body is non-scrollable (common interstitial trick)
                    var stB = window.getComputedStyle(b);
                    var stH = window.getComputedStyle(de);
                    if (stB && (stB.overflow === 'hidden' || stB.overflowY === 'hidden')) {
                      b.style.setProperty('overflow', 'auto', 'important');
                      b.style.setProperty('overflow-y', 'auto', 'important');
                    }
                    if (stH && (stH.overflow === 'hidden' || stH.overflowY === 'hidden')) {
                      de.style.setProperty('overflow', 'auto', 'important');
                      de.style.setProperty('overflow-y', 'auto', 'important');
                    }
                    // Clear common scroll-lock classes
                    b.classList.remove('modal-open','no-scroll','noscroll','overflow-hidden','body-scroll-lock');
                    de.classList.remove('modal-open','no-scroll','noscroll','overflow-hidden');
                  } catch(e) {}
                }

                function kill(el) {
                  try {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
                    el.style.setProperty('pointer-events', 'none', 'important');
                    el.setAttribute('data-csvtv-ab', '1');
                  } catch(e) {}
                }

                function sweep() {
                  ensureStyle();
                  try {
                    var nodes = document.querySelectorAll('body *');
                    // Cap work on huge DOMs
                    var n = Math.min(nodes.length, 2500);
                    for (var i = 0; i < n; i++) {
                      var el = nodes[i];
                      if (el.getAttribute && el.getAttribute('data-csvtv-ab') === '1') continue;
                      if (looksLikeSpamOverlay(el)) kill(el);
                    }
                  } catch(e) {}
                  unlockScroll();
                }

                window.__csvtvAbSweep = sweep;

                // Block window.open spam when possible (user gesture still needed for some)
                try {
                  var realOpen = window.open;
                  window.open = function() {
                    try {
                      console.log('csvtv-ab: blocked window.open');
                    } catch(e) {}
                    return null;
                  };
                  window.__csvtvAbRealOpen = realOpen;
                } catch(e) {}

                // alert/confirm spam
                try {
                  window.alert = function(){};
                  window.confirm = function(){ return true; };
                } catch(e) {}

                ensureStyle();
                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', sweep);
                } else {
                  sweep();
                }

                try {
                  var mo = new MutationObserver(function() {
                    if (window.__csvtvAbMoTimer) return;
                    window.__csvtvAbMoTimer = setTimeout(function() {
                      window.__csvtvAbMoTimer = null;
                      sweep();
                    }, 300);
                  });
                  mo.observe(document.documentElement, { childList: true, subtree: true });
                } catch(e) {}

                // Delayed popups (many fire 1–5s after load)
                var times = [500, 1500, 3000, 6000, 10000];
                for (var t = 0; t < times.length; t++) {
                  setTimeout(sweep, times[t]);
                }
                setInterval(sweep, 8000);
              } catch(e) {
                try { console.log('csvtv-ab error', e); } catch(x) {}
              }
            })();
        """.trimIndent()
    }

    // endregion

    private fun readSourceText(context: Context, source: AdBlockSource): String? {
        return when {
            source.isAsset -> {
                val path = source.path ?: return null
                try {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Log.w(TAG, "Asset missing: $path", e)
                    null
                }
            }
            source.isRemote -> {
                val cache = cacheFile(context, source.id)
                if (cache.exists() && cache.length() > 0) {
                    cache.readText()
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun cacheFile(context: Context, sourceId: String): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        // Sanitize id for filesystem
        val safe = sourceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safe.txt")
    }

    // endregion

    // region Rule parsing (also used by tests)

    /**
     * Parse blocklist text into host set and path/substring list.
     * Supports hosts-file lines, plain domains, path fragments, and ||domain^ anchors.
     */
    internal fun parseRulesInto(
        text: String,
        hostSet: MutableSet<String>,
        pathList: MutableList<String>
    ) {
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("!")) continue

            // EasyList domain anchor: ||example.com^ or ||example.com^$third-party
            if (line.startsWith("||")) {
                val body = line.removePrefix("||")
                val end = body.indexOf('^').let { if (it >= 0) it else body.indexOf('$').let { d -> if (d >= 0) d else body.length } }
                val domain = body.substring(0, end).trim().lowercase().trim('.')
                if (isPlausibleHost(domain) && !domain.contains("/")) {
                    hostSet.add(domain)
                }
                continue
            }

            // Skip other EasyList cosmetic / complex filters
            if (line.startsWith("@@") || line.contains("##") || line.contains("#@#")) continue
            // EasyList regex filters are /pattern/ with metacharacters — keep plain paths like /pagead/
            if (line.startsWith("/") && line.length > 2) {
                val lastSlash = line.lastIndexOf('/')
                if (lastSlash > 0) {
                    val body = line.substring(1, lastSlash)
                    if (body.any { it in "()[]{}.*+?|\\" }) continue
                }
            }

            // hosts file: "0.0.0.0 domain" / "127.0.0.1 domain"
            if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") || line.startsWith("::1") ||
                line.startsWith("255.255.255.255")
            ) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val domain = parts[1].lowercase().trim('.')
                    if (isPlausibleHost(domain)) hostSet.add(domain)
                }
                continue
            }

            // Strip inline comments
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash).trim()
            if (line.isEmpty()) continue

            // URL substring / path rule
            if (line.contains("/")) {
                pathList.add(line.lowercase())
                continue
            }

            // Plain host
            val host = line.lowercase().trim('.')
            if (isPlausibleHost(host)) {
                hostSet.add(host)
            }
        }
    }

    /** Test helper: load rules from lines without Android context. */
    internal fun loadFromLinesForTest(lines: List<String>) {
        val hostSet = HashSet<String>()
        val pathList = ArrayList<String>()
        parseRulesInto(lines.joinToString("\n"), hostSet, pathList)
        hosts = hostSet
        urlContains = pathList.map { it.lowercase() }.distinct().toTypedArray()
        loaded = true
    }

    internal fun clearForTest() {
        hosts = emptySet()
        urlContains = emptyArray()
        loaded = false
    }

    private fun isPlausibleHost(host: String): Boolean {
        if (host.length < 3 || host.length > 253) return false
        if (!host.contains('.')) return false
        if (host.startsWith(".") || host.endsWith(".")) return false
        // Reject pure IPs as block targets (optional; some lists use them)
        if (host.all { it.isDigit() || it == '.' }) return false
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    // endregion

    // region User blocked sites (TV-friendly, no files)

    /** Domains the user added in Settings or from the browser. Sorted A–Z. */
    fun getUserHosts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_USER_HOSTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optString(i, "").lowercase().trim('.')
                if (isPlausibleHost(h)) list.add(h)
            }
            list.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Add a domain from free text or a full URL (e.g. `ads.foo.com` or `https://ads.foo.com/x`).
     * @return normalized host on success, null if invalid / duplicate / full
     */
    fun addUserHost(context: Context, hostOrUrl: String): String? {
        val host = normalizeUserHostInput(hostOrUrl) ?: return null
        val current = getUserHosts(context).toMutableList()
        if (current.any { it.equals(host, ignoreCase = true) }) {
            return host // already present — treat as success for UX
        }
        if (current.size >= MAX_USER_HOSTS) return null
        current.add(host)
        saveUserHosts(context, current)
        reload(context)
        return host
    }

    fun removeUserHost(context: Context, host: String): Boolean {
        val target = host.lowercase().trim('.')
        val current = getUserHosts(context)
        val next = current.filterNot { it == target }
        if (next.size == current.size) return false
        saveUserHosts(context, next)
        reload(context)
        return true
    }

    fun clearUserHosts(context: Context) {
        saveUserHosts(context, emptyList())
        reload(context)
    }

    fun isUserHost(context: Context, host: String): Boolean {
        val h = host.lowercase().trim('.')
        return getUserHosts(context).any { it == h }
    }

    /**
     * Normalize user input into a blockable host.
     * Accepts bare domains or full URLs.
     */
    fun normalizeUserHostInput(hostOrUrl: String): String? {
        var s = hostOrUrl.trim()
        if (s.isEmpty()) return null
        // If it looks like a URL or path, extract host
        if (s.contains("://") || s.startsWith("//") || s.contains("/")) {
            extractHost(s)?.let { return it.takeIf { h -> isPlausibleHost(h) } }
        }
        // Strip accidental scheme leftovers and www. optional keep as-is
        s = s.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('.')
        val slash = s.indexOfAny(charArrayOf('/', '?', '#'))
        if (slash >= 0) s = s.substring(0, slash)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        s = s.trim('.')
        return s.takeIf { isPlausibleHost(it) }
    }

    private fun saveUserHosts(context: Context, hosts: List<String>) {
        val arr = JSONArray()
        for (h in hosts.distinct().sorted()) {
            arr.put(h)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_HOSTS, arr.toString())
            .apply()
    }

    // endregion

    // region Source management (list packs — internal download, UI is ON/OFF)

    fun getSources(context: Context): List<AdBlockSource> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SOURCES, null)
        if (!stored.isNullOrBlank()) {
            try {
                return parseSourcesJson(stored)
            } catch (e: Exception) {
                Log.w(TAG, "Corrupt sources prefs, resetting", e)
            }
        }
        val defaults = loadDefaultSourcesFromAssets(context)
        saveSources(context, defaults)
        return defaults
    }

    fun setSourceEnabled(context: Context, id: String, enabled: Boolean) {
        val updated = getSources(context).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveSources(context, updated)
        reload(context)
    }

    fun addCustomSource(context: Context, name: String, url: String): Boolean {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return false
        }
        val id = "custom_" + System.currentTimeMillis()
        val displayName = name.trim().ifEmpty { "Custom list" }
        val sources = getSources(context).toMutableList()
        sources.add(
            AdBlockSource(
                id = id,
                name = displayName,
                type = "remote",
                url = trimmedUrl,
                enabled = true,
                isCustom = true
            )
        )
        saveSources(context, sources)
        return true
    }

    fun removeSource(context: Context, id: String): Boolean {
        val sources = getSources(context)
        val target = sources.find { it.id == id } ?: return false
        if (!target.isCustom && target.isAsset) return false // keep builtin asset entry
        // Allow removing custom remotes; for built-in remotes just disable
        val updated = if (target.isCustom) {
            cacheFile(context, id).delete()
            sources.filterNot { it.id == id }
        } else {
            sources.map { if (it.id == id) it.copy(enabled = false) else it }
        }
        saveSources(context, updated)
        reload(context)
        return true
    }

    /**
     * Reset list packs to the catalog shipped in assets.
     * @param clearUserHosts if true, also clears My blocked sites
     */
    fun resetSourcesToDefault(context: Context, clearUserHosts: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().remove(KEY_SOURCES).remove(KEY_LAST_AUTO_UPDATE)
        if (clearUserHosts) editor.remove(KEY_USER_HOSTS)
        editor.apply()
        // Keep downloaded caches; reload will re-read defaults + existing caches
        val defaults = loadDefaultSourcesFromAssets(context)
        // Preserve lastUpdated/ruleCount from cache files if present
        val withMeta = defaults.map { src ->
            if (src.isRemote) {
                val f = cacheFile(context, src.id)
                if (f.exists()) src.copy(lastUpdatedMs = f.lastModified(), ruleCount = countRulesRough(f.readText()))
                else src
            } else src
        }
        saveSources(context, withMeta)
        reload(context)
    }

    fun isUpdateInProgress(): Boolean = updateInProgress.get()

    /**
     * Download all enabled remote sources, then reload matcher.
     * [callback] is invoked on the calling thread's completion path (background).
     */
    fun updateAllRemote(context: Context, callback: (ok: Boolean, message: String) -> Unit) {
        if (!updateInProgress.compareAndSet(false, true)) {
            callback(false, "Update already in progress")
            return
        }
        val app = context.applicationContext
        Thread {
            try {
                val sources = getSources(app)
                var okCount = 0
                var failCount = 0
                val updatedList = sources.toMutableList()
                for (i in updatedList.indices) {
                    val src = updatedList[i]
                    if (!src.enabled || !src.isRemote || src.url.isNullOrBlank()) continue
                    val result = downloadSource(app, src)
                    updatedList[i] = result
                    if (result.lastError == null) okCount++ else failCount++
                }
                saveSources(app, updatedList)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_AUTO_UPDATE, System.currentTimeMillis())
                    .apply()
                reload(app)
                val (h, p) = ruleStats()
                callback(
                    failCount == 0,
                    "Updated $okCount list(s)" +
                        (if (failCount > 0) ", $failCount failed" else "") +
                        " · $h hosts, $p path rules"
                )
            } catch (e: Exception) {
                Log.e(TAG, "updateAllRemote failed", e)
                callback(false, "Update failed: ${e.message}")
            } finally {
                updateInProgress.set(false)
            }
        }.start()
    }

    fun updateSingleRemote(context: Context, id: String, callback: (ok: Boolean, message: String) -> Unit) {
        if (!updateInProgress.compareAndSet(false, true)) {
            callback(false, "Update already in progress")
            return
        }
        val app = context.applicationContext
        Thread {
            try {
                val sources = getSources(app).toMutableList()
                val idx = sources.indexOfFirst { it.id == id }
                if (idx < 0) {
                    callback(false, "Source not found")
                    return@Thread
                }
                val src = sources[idx]
                if (!src.isRemote || src.url.isNullOrBlank()) {
                    callback(false, "Not a remote source")
                    return@Thread
                }
                val result = downloadSource(app, src)
                sources[idx] = result
                saveSources(app, sources)
                reload(app)
                if (result.lastError == null) {
                    callback(true, "${result.name}: ${result.ruleCount} rules")
                } else {
                    callback(false, result.lastError ?: "Download failed")
                }
            } catch (e: Exception) {
                callback(false, e.message ?: "Error")
            } finally {
                updateInProgress.set(false)
            }
        }.start()
    }

    private fun downloadSource(context: Context, source: AdBlockSource): AdBlockSource {
        val url = source.url ?: return source.copy(lastError = "No URL")
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TV-CSV-AdBlocker/1.0")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return source.copy(lastError = "HTTP ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return source.copy(lastError = "Empty response")
                }
                val cache = cacheFile(context, source.id)
                cache.writeText(body)
                val count = countRulesRough(body)
                source.copy(
                    lastUpdatedMs = System.currentTimeMillis(),
                    ruleCount = count,
                    lastError = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${source.id}: ${e.message}")
            source.copy(lastError = e.message ?: "Download error")
        }
    }

    private fun countRulesRough(text: String): Int {
        val hosts = HashSet<String>()
        val paths = ArrayList<String>()
        parseRulesInto(text, hosts, paths)
        return hosts.size + paths.size
    }

    private fun maybeAutoUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_AUTO_UPDATE, 0L)
        val intervalDays = loadAutoUpdateDays(context)
        val intervalMs = intervalDays * 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        val needsUpdate = (now - last) >= intervalMs

        val sources = getSources(context)
        val anyRemoteEnabledMissingCache = sources.any {
            it.enabled && it.isRemote && !cacheFile(context, it.id).exists()
        }

        if (!needsUpdate && !anyRemoteEnabledMissingCache) return
        if (updateInProgress.get()) return

        Log.i(TAG, "Auto-updating remote adblock lists (stale=$needsUpdate, missingCache=$anyRemoteEnabledMissingCache)")
        updateAllRemote(context) { ok, msg ->
            Log.i(TAG, "Auto-update finished ok=$ok $msg")
        }
    }

    private fun loadAutoUpdateDays(context: Context): Long {
        return try {
            context.assets.open(SOURCES_ASSET).bufferedReader().use { it.readText() }
                .let { JSONObject(it).optLong("autoUpdateIntervalDays", DEFAULT_AUTO_UPDATE_DAYS) }
                .coerceAtLeast(1L)
        } catch (_: Exception) {
            DEFAULT_AUTO_UPDATE_DAYS
        }
    }

    private fun loadDefaultSourcesFromAssets(context: Context): List<AdBlockSource> {
        return try {
            val json = context.assets.open(SOURCES_ASSET).bufferedReader().use { it.readText() }
            parseSourcesJson(json).map { it.copy(isCustom = false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $SOURCES_ASSET", e)
            // Minimal fallback if asset missing — still no domain hardcoding, only the asset path
            listOf(
                AdBlockSource(
                    id = "builtin",
                    name = "Built-in defaults",
                    type = "asset",
                    path = "adblock/default.txt",
                    enabled = true
                )
            )
        }
    }

    private fun parseSourcesJson(json: String): List<AdBlockSource> {
        val root = JSONObject(json)
        val arr: JSONArray = when {
            root.has("sources") -> root.getJSONArray("sources")
            else -> JSONArray(json)
        }
        val list = ArrayList<AdBlockSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                AdBlockSource(
                    id = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    type = o.optString("type", if (o.has("url") && !o.isNull("url")) "remote" else "asset"),
                    path = o.optionalString("path"),
                    url = o.optionalString("url"),
                    enabled = o.optBoolean("enabled", true),
                    lastUpdatedMs = o.optLong("lastUpdatedMs", 0L),
                    ruleCount = o.optInt("ruleCount", 0),
                    lastError = o.optionalString("lastError"),
                    isCustom = o.optBoolean("isCustom", false)
                )
            )
        }
        return list
    }

    private fun saveSources(context: Context, sources: List<AdBlockSource>) {
        val arr = JSONArray()
        for (s in sources) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("type", s.type)
                    put("path", s.path)
                    put("url", s.url)
                    put("enabled", s.enabled)
                    put("lastUpdatedMs", s.lastUpdatedMs)
                    put("ruleCount", s.ruleCount)
                    put("lastError", s.lastError)
                    put("isCustom", s.isCustom)
                }
            )
        }
        val root = JSONObject().put("sources", arr)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCES, root.toString())
            .apply()
    }

    fun formatLastUpdated(ms: Long): String {
        if (ms <= 0L) return "never"
        val ago = System.currentTimeMillis() - ms
        val minutes = ago / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 48 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return v.takeIf { it.isNotBlank() && it != "null" }
    }

    // endregion
}
