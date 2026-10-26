package com.mmhw.csvtv

/**
 * Viewport and pan helpers for the TV WebView.
 *
 * Desktop sites often declare `width=device-width, initial-scale=1`, which
 * locks layout to the TV's CSS pixel width. Forcing a desktop width with
 * `initial-scale=1` and zoom disabled then clips anything wider/taller than
 * the view — the page looks like a fixed-width snapshot you cannot pan.
 */
object WebViewDisplayPolicy {

    const val DESKTOP_MIN_LAYOUT_WIDTH = 1280

    /**
     * Desktop layout width without pinning scale at 1.0, so
     * [android.webkit.WebSettings.loadWithOverviewMode] can shrink the page
     * to fit. `user-scalable` lets remaining overflow be panned.
     */
    fun desktopViewportJs(): String = """
        (function() {
            try {
                var inner = Math.max(
                    window.innerWidth || 0,
                    document.documentElement ? document.documentElement.clientWidth : 0
                );
                var layoutWidth = Math.max($DESKTOP_MIN_LAYOUT_WIDTH, inner);
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    (document.head || document.documentElement).appendChild(meta);
                }
                meta.setAttribute(
                    'content',
                    'width=' + layoutWidth +
                    ', user-scalable=yes, minimum-scale=0.25, maximum-scale=5.0'
                );
                var de = document.documentElement;
                var b = document.body;
                if (de && b) {
                    var needX = Math.max(de.scrollWidth, b.scrollWidth) > (window.innerWidth || de.clientWidth) + 4;
                    var needY = Math.max(de.scrollHeight, b.scrollHeight) > (window.innerHeight || de.clientHeight) + 4;
                    if (needX) {
                        de.style.setProperty('overflow-x', 'auto', 'important');
                        b.style.setProperty('overflow-x', 'auto', 'important');
                    }
                    if (needY) {
                        de.style.setProperty('overflow-y', 'auto', 'important');
                        b.style.setProperty('overflow-y', 'auto', 'important');
                    }
                }
            } catch (e) {}
        })();
    """.trimIndent()

    fun contentSizeJs(): String = """
        (function() {
            var de = document.documentElement;
            var b = document.body;
            var w = Math.max(
                de ? de.scrollWidth : 0,
                b ? b.scrollWidth : 0,
                de ? de.clientWidth : 0
            );
            var h = Math.max(
                de ? de.scrollHeight : 0,
                b ? b.scrollHeight : 0,
                de ? de.clientHeight : 0
            );
            return {width: w, height: h};
        })();
    """.trimIndent()

    /**
     * Scroll the element under the pointer, then the document, then the
     * largest remaining scroller. Returns true if anything moved.
     */
    fun pageScrollJs(cssX: Int, cssY: Int, dx: Int, dy: Int): String = """
        (function() {
            var ptX = $cssX;
            var ptY = $cssY;
            var dx = $dx;
            var dy = $dy;
            function canScroll(el, x, y) {
                if (!el) return false;
                var style;
                try { style = window.getComputedStyle(el); } catch (e) { return false; }
                if (!style) return false;
                var ox = style.overflowX, oy = style.overflowY;
                var allowX = ox === 'auto' || ox === 'scroll' || ox === 'overlay' ||
                    (el.scrollWidth > el.clientWidth + 1);
                var allowY = oy === 'auto' || oy === 'scroll' || oy === 'overlay' ||
                    (el.scrollHeight > el.clientHeight + 1);
                if (x !== 0 && allowX && el.scrollWidth > el.clientWidth) {
                    var left = el.scrollLeft;
                    var max = el.scrollWidth - el.clientWidth;
                    if ((x < 0 && left > 0) || (x > 0 && left < max - 0.5)) return true;
                }
                if (y !== 0 && allowY && el.scrollHeight > el.clientHeight) {
                    var top = el.scrollTop;
                    var maxY = el.scrollHeight - el.clientHeight;
                    if ((y < 0 && top > 0) || (y > 0 && top < maxY - 0.5)) return true;
                }
                return false;
            }
            function tryScroll(el, x, y) {
                if (!el) return false;
                var oldL = el.scrollLeft || 0;
                var oldT = el.scrollTop || 0;
                try {
                    if (el.scrollBy) el.scrollBy(x, y);
                    else {
                        el.scrollLeft = oldL + x;
                        el.scrollTop = oldT + y;
                    }
                } catch (e) {
                    try {
                        el.scrollLeft = oldL + x;
                        el.scrollTop = oldT + y;
                    } catch (e2) {}
                }
                return Math.abs((el.scrollLeft || 0) - oldL) > 0.5 ||
                       Math.abs((el.scrollTop || 0) - oldT) > 0.5;
            }
            var el = document.elementFromPoint(ptX, ptY);
            while (el && el !== document.documentElement) {
                if (canScroll(el, dx, dy) && tryScroll(el, dx, dy)) return true;
                el = el.parentElement;
            }
            var root = document.scrollingElement || document.documentElement || document.body;
            if (tryScroll(root, dx, dy)) return true;
            if (document.body && root !== document.body && tryScroll(document.body, dx, dy)) return true;
            var beforeX = window.pageXOffset || 0;
            var beforeY = window.pageYOffset || 0;
            window.scrollBy(dx, dy);
            if (Math.abs((window.pageXOffset || 0) - beforeX) > 0.5 ||
                Math.abs((window.pageYOffset || 0) - beforeY) > 0.5) return true;
            try {
                var nodes = document.querySelectorAll('body *');
                var best = null, bestArea = 0;
                var n = Math.min(nodes.length, 2000);
                for (var i = 0; i < n; i++) {
                    var node = nodes[i];
                    if (!canScroll(node, dx, dy)) continue;
                    var area = (node.clientWidth || 0) * (node.clientHeight || 0);
                    if (area > bestArea) { bestArea = area; best = node; }
                }
                if (best && tryScroll(best, dx, dy)) return true;
            } catch (e) {}
            return false;
        })();
    """.trimIndent()
}
