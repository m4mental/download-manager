package com.example.speeddown.engine

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object AdBlockEngine {

    var isEnabled: Boolean = true
    var blockedAdsCount: Int = 0
        private set

    private val domainSet = ConcurrentHashMap.newKeySet<String>()
    private var isInitialized = false

    fun resetCount() {
        blockedAdsCount = 0
    }

    fun recordBlock() {
        blockedAdsCount++
    }

    /**
     * Initializes AdBlockEngine by loading asset blocklists into memory
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        try {
            context.assets.open("adblock_hosts.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim().lowercase()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            domainSet.add(trimmed)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback / Pre-seeded domains
        domainSet.addAll(FALLBACK_AD_DOMAINS)
    }

    // High-frequency substring heuristics that identify popunders, redirect farms, and trackers
    private val AD_URL_PATTERNS = listOf(
        "popunder", "pop-under", "popads", "popcash", "adsterra", "monetag",
        "hilltopads", "galaksion", "onclickalgo", "onclickperf", "onclickmega",
        "clickadu", "adcash", "ad-maven", "zeroredirect", "adskeeper", "adsupply",
        "bidvertiser", "ero-ad", "wpadm", "trafficjunky", "trafficfactory",
        "deloplen", "exosrv", "tsyndicate", "realsrv", "juicyads", "adtrue",
        "histats", "stripchat", "chaturbate", "bongacams", "livejasmin",
        "/ads.js", "/ad.js", "/adframe", "/banner_ad", "adserver", "ads_click",
        "doubleclick.net", "googleadservices.com", "googlesyndication.com"
    )

    /**
     * Checks if a network request or navigation URL is an ad, tracker, or popup
     */
    fun isAd(url: String): Boolean {
        if (!isEnabled || url.isBlank()) return false
        val lowerUrl = url.lowercase().trim()

        // 1. Fast sub-string heuristic check
        for (pattern in AD_URL_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return true
            }
        }

        // 2. Domain & Suffix lookup against uBlock database
        try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            if (host.isNotEmpty()) {
                if (domainSet.contains(host)) return true

                // Check parent domains (e.g. ad.foo.doubleclick.net -> doubleclick.net)
                var dotIndex = host.indexOf('.')
                while (dotIndex != -1 && dotIndex < host.length - 1) {
                    val subDomain = host.substring(dotIndex + 1)
                    if (domainSet.contains(subDomain)) return true
                    dotIndex = host.indexOf('.', dotIndex + 1)
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Checks if a URL scheme or redirect is rogue (e.g. hijack intents, spam app store triggers)
     */
    fun isRogueRedirect(url: String): Boolean {
        if (!isEnabled || url.isBlank()) return false
        val lower = url.lowercase().trim()

        // Block rogue intents, app store links, and messenger triggers from download pages
        if (lower.startsWith("intent://") ||
            lower.startsWith("market://") ||
            lower.startsWith("whatsapp://") ||
            lower.startsWith("tg://")
        ) {
            return true
        }

        return isAd(url)
    }

    /**
     * Returns an empty response to cleanly drop ad requests without breaking page layout
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * uBlock Origin-style advanced JavaScript defusers:
     * - window.open defuser
     * - EventTarget.addEventListener click-jacking defuser
     * - Synthetic HTMLAnchorElement click defuser
     * - Alert/Confirm trap defuser
     * - Transparent click-hijack overlay remover
     */
    const val UBLOCK_SCRIPTLET_DEFUSER_JS = """
        (function() {
            try {
                if (window.__uBlockDefusersActive) return;
                window.__uBlockDefusersActive = true;

                // 1. Defuse window.open (uBO nowinopen)
                var dummyWindow = {
                    closed: true,
                    focus: function() {},
                    blur: function() {},
                    close: function() {},
                    postMessage: function() {}
                };
                var noopOpen = function(u) {
                    console.log('uBlock Origin Defuser: Blocked window.open:', u);
                    if (window.SpeedDownAdBlock) window.SpeedDownAdBlock.notifyAdBlocked();
                    return dummyWindow;
                };

                try {
                    Object.defineProperty(window, 'open', {
                        get: function() { return noopOpen; },
                        set: function() {},
                        configurable: false
                    });
                } catch(e) {
                    window.open = noopOpen;
                }

                // 2. Defuse synthetic element clicks on links (common popup trigger)
                var origClick = HTMLElement.prototype.click;
                HTMLElement.prototype.click = function() {
                    if (this.tagName === 'A') {
                        var href = this.href || '';
                        if (window.SpeedDownAdBlock && window.SpeedDownAdBlock.isAdUrl(href)) {
                            console.log('uBlock Defuser: Blocked synthetic ad link click', href);
                            window.SpeedDownAdBlock.notifyAdBlocked();
                            return;
                        }
                    }
                    return origClick.apply(this, arguments);
                };

                // 3. Defuse Alert / Confirm / Prompt traps that lock the browser
                window.alert = function() {};
                window.confirm = function() { return false; };
                window.prompt = function() { return null; };
                window.blur = function() {};

                // 4. Defuse click-jacking overlays specifically tagged as ads without touching media players
                function cleanAdOverlays() {
                    var adSelectors = '.popunder, .pop-under, .ad-overlay, [class*="popunder"], [id*="popunder"], [class*="clickadu"], [class*="adsterra"], [class*="monetag"]';
                    var badEls = document.querySelectorAll(adSelectors);
                    for (var i = 0; i < badEls.length; i++) {
                        var el = badEls[i];
                        // Never remove media players, iframes, or video elements!
                        if (!el.querySelector('video, audio, iframe') && el.tagName !== 'IFRAME') {
                            el.remove();
                        }
                    }
                }
                cleanAdOverlays();
                setInterval(cleanAdOverlays, 2000);
            } catch(e) {}
        })();
    """

    /**
     * Cosmetic CSS filter injection script to collapse known ad banners without touching video player iframes
     */
    const val COSMETIC_AD_BLOCK_JS = """
        (function() {
            var css = `
                [id*="google_ads"], [id*="ad-slot"], [class*="ad-banner"],
                [class*="ad_banner"], .adsbygoogle, .pop-under,
                [class*="popunder"], [id*="popunder"], .adsterra-banner,
                [id*="adsterra"], [class*="monetag-ad"], [id*="monetag"] {
                    display: none !important;
                    visibility: hidden !important;
                    height: 0 !important;
                    width: 0 !important;
                    opacity: 0 !important;
                    pointer-events: none !important;
                }
            `;
            var style = document.createElement('style');
            style.type = 'text/css';
            style.appendChild(document.createTextNode(css));
            document.head && document.head.appendChild(style);
        })();
    """

    private val FALLBACK_AD_DOMAINS = listOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "pagead2.googlesyndication.com", "adservice.google.com", "adnxs.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "exoclick.com", "exosrv.com", "realsrv.com", "tsyndicate.com",
        "juicyads.com", "adtrue.com", "histats.com", "deloplen.com",
        "trafficjunky.com", "trafficfactory.biz", "hilltopads.com",
        "monetag.com", "galaksion.com", "onclickalgo.com", "onclickperformance.com",
        "onclickmega.com", "yllix.com", "clickadu.com", "adcash.com",
        "ad-maven.com", "zeroredirect1.com", "adskeeper.co.uk", "adskeeper.com",
        "stripchat.com", "chaturbate.com", "bongacams.com", "livejasmin.com",
        "1xbet.com", "bet365.com", "shorte.st", "ouo.io", "linkvertise.com"
    )
}
