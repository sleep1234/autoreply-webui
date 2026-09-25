package dev.example.autoreply.ctwing

/**
 * The JavaScript payload injected into CTWing H5 WebView.
 *
 * Two modes of operation:
 *
 * 1. DIAGNOSTIC (default) — hooks XHR/fetch to capture plaintext request
 *    bodies (pre-encryption) and plaintext responses (post-decryption),
 *    then reports the SPA's global surface (axios/Vue/encryption SDK) via
 *    `onDiagnostic`. This tells us the *real* request mechanism and lets
 *    us discover the exact API the SPA uses, including how ICCID is encoded.
 *
 * 2. DIRECT CALL — once the request shape is known, `window.__ctwing.*`
 *    methods call the SPA's own HTTP client (NOT raw fetch) so the CTROBF1
 *    encryption is applied/decrypted by the SPA's interceptor automatically.
 *
 * The injected JS exposes `window.__ctwing` which the Java side can call
 * through `evaluateJavascript`:
 *
 *   __ctwing.queryCard(iccid)   → calls SPA client, resolves plaintext JSON
 *   __ctwing.diagnose(iccid)
 *   __ctwing.rebind(iccid, imei)
 *   __ctwing.dump()             → returns SPA global surface as JSON
 *
 * Each API call returns a Promise that resolves to a JSON string; the JS
 * also posts results to the Java bridge via `window.__ctwingBridge`.
 */
object CtwingJsInjector {

    const val BRIDGE_NAME = "ctwingBridge"

    fun build(): String = """
    (function() {
        if (window.__ctwingInjected) return;
        window.__ctwingInjected = true;

        const LOG = (m) => { try { window.$BRIDGE_NAME && window.$BRIDGE_NAME.log(String(m)); } catch(e){} };
        const DIAG = (m) => { try { window.$BRIDGE_NAME && window.$BRIDGE_NAME.onDiagnostic(typeof m === 'string' ? m : JSON.stringify(m)); } catch(e){} };

        const API_BASE = 'https://tywlonestop.ctwing.cn:8081/webapp-font/admin-api/bpm/service-assistant';

        // ============================================================
        // 1. DIAGNOSTIC HOOKS — capture plaintext in/out
        // ============================================================

        // --- Hook XMLHttpRequest.load to capture the FINAL response body.
        //     At this point, if the SPA uses XHR + its own decryption, the
        //     body may be plaintext. We report either way for diagnosis.
        const origXHROpen = XMLHttpRequest.prototype.open;
        const origXHRSend = XMLHttpRequest.prototype.send;
        const origXHRSetHeader = XMLHttpRequest.prototype.setRequestHeader;

        XMLHttpRequest.prototype.open = function(method, url) {
            this.__ctwing = { method: String(method), url: String(url) };
            return origXHROpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (this.__ctwing) {
                this.__ctwing.headers = this.__ctwing.headers || {};
                this.__ctwing.headers[String(name)] = String(value);
            }
            return origXHRSetHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function(body) {
            if (this.__ctwing) {
                this.__ctwing.requestBody = body ? String(body) : null;
            }
            this.addEventListener('load', function() {
                const meta = this.__ctwing || {};
                const url = meta.url || '';
                if (url.indexOf('ctwing.cn') < 0) return;
                const resp = this.responseText || '';
                // Push the FULL response to the api-capture channel
                try { window.$BRIDGE_NAME && window.$BRIDGE_NAME.onApiCapture(resp); } catch(e){}
                const diag = {
                    kind: 'xhr-load',
                    method: meta.method,
                    url: url.substring(0, 300),
                    status: this.status,
                    requestBody: meta.requestBody ? meta.requestBody.substring(0, 1500) : null,
                    requestBodyHead: meta.requestBody ? meta.requestBody.substring(0, 200) : null,
                    responseHead: resp.substring(0, 200),
                    responseLen: resp.length,
                    headers: meta.headers || {}
                };
                DIAG(diag);
            });
            return origXHRSend.apply(this, arguments);
        };

        // --- Hook fetch to capture request/response ---
        const origFetch = window.fetch;
        if (origFetch) {
            window.fetch = function(url, opts) {
                const method = (opts && opts.method) || 'GET';
                const reqBody = (opts && opts.body) ? String(opts.body) : null;
                const p = origFetch.apply(this, arguments);
                p.then(function(resp) {
                    if (String(url).indexOf('ctwing.cn') < 0) return resp;
                    const clone = resp.clone();
                    clone.text().then(function(text) {
                        try { window.$BRIDGE_NAME && window.$BRIDGE_NAME.onApiCapture(text); } catch(e){}
                        DIAG({
                            kind: 'fetch-resp',
                            method: method,
                            url: String(url).substring(0, 300),
                            status: resp.status,
                            requestBody: reqBody ? reqBody.substring(0, 1500) : null,
                            responseHead: text.substring(0, 200),
                            responseLen: text.length
                        });
                    }).catch(function(){});
                    return resp;
                }).catch(function(){});
                return p;
            };
        }

        // ============================================================
        // 2. UI AUTOMATION — find page input + button, fill ICCID, click
        //    This triggers the SPA's own request pipeline so encryption
        //    happens automatically and our hooks capture the plaintext.
        // ============================================================

        function findInput() {
            // Try common input selectors
            var el = document.querySelector('input[type="text"]');
            if (!el) el = document.querySelector('input:not([type="hidden"])');
            if (!el) el = document.querySelector('.van-field__control');
            if (!el) el = document.querySelector('.el-input__inner');
            if (!el) {
                var inputs = document.querySelectorAll('input');
                for (var i=0; i<inputs.length; i++) {
                    if (inputs[i].type !== 'hidden') { el = inputs[i]; break; }
                }
            }
            return el;
        }

        function findButton() {
            // Try common button selectors
            var btn = document.querySelector('button');
            if (!btn) {
                var btns = document.querySelectorAll('[role="button"]');
                if (btns.length > 0) btn = btns[btns.length-1];
            }
            if (!btn) {
                var spans = document.querySelectorAll('span,div');
                for (var i=0; i<spans.length; i++) {
                    var t = (spans[i].textContent||'').trim();
                    if (t==='查询'||t==='搜索'||t==='诊断'||t==='提 交'||t==='提交') {
                        btn = spans[i]; break;
                    }
                }
            }
            return btn;
        }

        // fill an input field with value, dispatching events so Vue/React detects it
        function fillInput(el, value) {
            if (!el) return false;
            // Vue v-model uses input event
            var nativeSetter = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value'
            );
            if (nativeSetter && nativeSetter.set) {
                nativeSetter.set.call(el, value);
            } else {
                el.value = value;
            }
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            // Also dispatch keyup for frameworks that listen on it
            el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
            return true;
        }

        function clickEl(el) {
            if (!el) return false;
            el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
            // Also try mousedown + mouseup sequence
            el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
            el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
            return true;
        }

        // ============================================================
        // 3. SPA GLOBAL SURFACE DISCOVERY
        // ============================================================

        function dumpSurface() {
            const out = { vue: false, axios: false, globals: [], ui: {} };
            try {
                const appEl = document.querySelector('#app') || document.body;
                if (appEl && appEl.__vue__) { out.vue = 2; out.vueHasHttp = !!(appEl.__vue__.${'$'}http || appEl.__vue__.http); }
                if (appEl && appEl.__vue_app__) { out.vue = 3; }
            } catch(e){}
            try {
                if (window.axios) { out.axios = true; out.axiosDefault = window.axios.defaults ? 'present' : 'no-defaults'; }
            } catch(e){}
            const candidates = ['ctDynamic', 'ctwing', 'encrypt', 'decrypt', 'CryptoJS',
                'smCrypto', 'request', 'http', 'api', 'getToken', 'sendRequest',
                '_enc', '_dec', 'security', 'obfuscate', 'deobfuscate'];
            for (const k of candidates) {
                if (window[k] !== undefined && window[k] !== null) {
                    out.globals.push({ name: k, type: typeof window[k] });
                }
            }
            try {
                const keys = Object.keys(window).filter(k =>
                    /ct|encrypt|decrypt|api|http|request|token|auth/i.test(k)
                ).slice(0, 60);
                out.windowKeys = keys;
            } catch(e){}
            // UI discovery
            var inp = findInput();
            var btn = findButton();
            out.ui = {
                inputFound: !!inp,
                inputSelector: inp ? (inp.id||inp.className||inp.tagName) : null,
                inputPlaceholder: inp ? inp.placeholder : null,
                buttonFound: !!btn,
                buttonText: btn ? (btn.textContent||'').trim().substring(0,30) : null,
                pageTitle: document.title || ''
            };
            return out;
        }

        // ============================================================
        // 4. CRYPTO RECON — deep hooking for CTROBF1 reverse-engineering
        // ============================================================

        // Generated key/counter for dynamic CTROBF1 headers
        let __ctwing_ts = Date.now() + '';
        let __ctwing_nonce = Math.random().toString(36).substring(2, 10);

        // ---- Capture buffer ----
        window.__ctwingRecon = {
            captured: false,
            cryptoConfig: null,
            plainRequest: null,
            encryptedRequest: null,
            plainResponse: null,
            encryptedResponse: null,
            headers: null
        };

        // ---- Monkey-patch CryptoJS.AES.encrypt to capture key/iv ----
        if (window.CryptoJS && window.CryptoJS.AES && window.CryptoJS.AES.encrypt) {
            var _origAesEncrypt = window.CryptoJS.AES.encrypt;
            window.CryptoJS.AES.encrypt = function(plaintext, key, cfg) {
                var config = {
                    keyHex: key ? (typeof key === 'string' ? key.substring(0, 80) : 'complex') : 'null',
                    ivHex: cfg && cfg.iv ? (typeof cfg.iv === 'string' ? cfg.iv : 'complex') : 'null',
                    mode: cfg && cfg.mode ? String(cfg.mode) : 'default',
                    padding: cfg && cfg.padding ? String(cfg.padding) : 'default',
                    plaintextPreview: String(plaintext||'').substring(0, 200)
                };
                LOG('CRYPTO-HOOK encrypt: key=' + config.keyHex + ' iv=' + config.ivHex + ' mode=' + config.mode);
                window.__ctwingRecon.cryptoConfig = config;
                window.__ctwingRecon.plainRequest = String(plaintext||'');
                var result = _origAesEncrypt.apply(this, arguments);
                window.__ctwingRecon.encryptedRequest = result ? result.toString() : null;
                return result;
            };
            LOG('recon: hooked CryptoJS.AES.encrypt');
        }

        if (window.CryptoJS && window.CryptoJS.AES && window.CryptoJS.AES.decrypt) {
            var _origAesDecrypt = window.CryptoJS.AES.decrypt;
            window.CryptoJS.AES.decrypt = function(ciphertext, key, cfg) {
                window.__ctwingRecon.encryptedResponse = ciphertext ? ciphertext.toString().substring(0, 400) : null;
                var result = _origAesDecrypt.apply(this, arguments);
                var dec = result ? result.toString(window.CryptoJS.enc.Utf8) : null;
                window.__ctwingRecon.plainResponse = dec;
                LOG('CRYPTO-HOOK decrypt: decryptedLen=' + (dec ? dec.length : 0));
                return result;
            };
            LOG('recon: hooked CryptoJS.AES.decrypt');
        }

        // ---- Hook native fetch to capture request/response headers ----
        var _origFetchRecon = window.fetch;
        window.fetch = function(url, opts) {
            var self = this;
            var args = arguments;
            var p = _origFetchRecon.apply(self, args);
            p.then(function(resp) {
                var urlStr = String(url || '');
                if (urlStr.indexOf('ctwing.cn') >= 0) {
                    var headers = {};
                    resp.headers.forEach(function(v, k) { headers[k] = v; });
                    window.__ctwingRecon.headers = {
                        url: urlStr.substring(0, 300),
                        method: (opts && opts.method) || 'GET',
                        requestHeaders: (opts && opts.headers) ? JSON.stringify(opts.headers) : '{}',
                        requestBody: (opts && opts.body) ? String(opts.body).substring(0, 2000) : null,
                        responseHeaders: JSON.stringify(headers),
                        status: resp.status
                    };
                    LOG('RECON-FETCH: ' + ((opts && opts.method) || 'GET') + ' ' + urlStr.substring(0, 150));
                }
            }).catch(function(){});
            return p;
        };

        // ---- Deep global scan: find ALL encryption-related globals ----
        function deepCryptoScan() {
            var result = { cryptoCandidates: [], allGlobals: [] };
            // Scan ALL window properties for anything crypto/encrypt related
            var skip = ['window', 'self', 'top', 'parent', 'frames', 'document', 'location', 'navigator',
                'localStorage', 'sessionStorage', 'crypto', 'CryptoJS'];
            var seen = {};
            for (var k in window) {
                try {
                    if (skip.indexOf(k) >= 0) continue;
                    var v = window[k];
                    var t = typeof v;
                    if (t === 'function' || t === 'object') {
                        var ks = k.toLowerCase();
                        if (ks.indexOf('encrypt') >= 0 || ks.indexOf('decrypt') >= 0 ||
                            ks.indexOf('crypt') >= 0 || ks.indexOf('cipher') >= 0 ||
                            ks.indexOf('obfuscat') >= 0 || ks.indexOf('security') >= 0 ||
                            ks.indexOf('dynamic') >= 0 || ks.indexOf('_ts') >= 0 ||
                            ks.indexOf('token') >= 0 || ks.indexOf('sign') >= 0 ||
                            ks.indexOf('hash') >= 0 || ks.indexOf('aes') >= 0) {
                            result.cryptoCandidates.push({ name: k, type: t });
                        }
                    }
                    if (t !== 'function' && t !== 'undefined') {
                        try { result.allGlobals.push({ name: k, type: t }); } catch(e) {}
                    }
                } catch(e) {}
            }
            // Also check __proto__ chains of common objects
            result.hasCryptoJS = !!(window.CryptoJS && window.CryptoJS.AES);
            result.hasSubtleCrypto = !!(window.crypto && window.crypto.subtle);

            // Try to find the CTROBF1 encoder/decoder
            var ctrobf = null;
            if (window.ctDynamic) ctrobf = { name: 'ctDynamic', hasEncrypt: !!window.ctDynamic.encrypt, hasDecrypt: !!window.ctDynamic.decrypt };
            else if (window._enc && window._dec) ctrobf = { name: '_enc/_dec pair', encType: typeof window._enc, decType: typeof window._dec };
            result.ctrobfHint = ctrobf;
            return result;
        }

        // ============================================================
        // 5. PUBLIC API SURFACE
        // ============================================================

        const __ctwing = {
            dump: function() {
                var s = dumpSurface();
                s.credentials = { token: localStorage.getItem('ctwing_token') || '', cookie: document.cookie || '' };
                return JSON.stringify(s);
            },

            // ---- New: crypto reconnaissance ----
            recon: function() {
                window.__ctwingRecon.captured = false;
                window.__ctwingRecon.cryptoConfig = null;
                var scan = deepCryptoScan();
                LOG('recon: found ' + scan.cryptoCandidates.length + ' crypto-like globals, ' + scan.allGlobals.length + ' total');
                return JSON.stringify(scan);
            },

            // ---- Retrieve the captured crypto data after a manual query ----
            reconReport: function() {
                return JSON.stringify(window.__ctwingRecon);
            },

            reportCredential: function() {
                try {
                    window.$BRIDGE_NAME && window.$BRIDGE_NAME.onCredential(
                        localStorage.getItem('ctwing_token') || sessionStorage.getItem('ctwing_token') || '',
                        document.cookie || ''
                    );
                } catch(e){}
                return localStorage.getItem('ctwing_token') || '';
            },

            // ---- UI automation: fill input + click button ----
            uiQuery: function(iccid) {
                return new Promise(function(resolve, reject) {
                    var inp = findInput();
                    if (!inp) { reject(new Error('找不到输入框（请先手动进入查询页面）')); return; }
                    var btn = findButton();
                    if (!btn) { reject(new Error('找不到查询按钮（请先手动进入查询页面）')); return; }
                    if (!fillInput(inp, iccid)) { reject(new Error('填写 ICCID 失败')); return; }
                    LOG('uiQuery: filled ICCID=' + iccid + ', clicking button text="' + (btn.textContent||'').trim().substring(0,20) + '"');
                    if (!clickEl(btn)) { reject(new Error('点击按钮失败')); return; }
                    // Wait for the SPA to load results (watch for DOM change or XHR)
                    // We'll resolve after a reasonable timeout — the caller uses
                    // result from diagnostic hooks (which fire asynchronously).
                    setTimeout(function() { resolve('{"_uiQuery":"dispatched","note":"check logcat [diag] for raw API response"}'); }, 3000);
                });
            },

            // Raw fetch calls (fallback when SPA client is unknown).
            rawQueryCard: function(iccid) {
                return fetch(API_BASE + '/querySimBaseInfo?iccid=' + encodeURIComponent(iccid), {
                    method: 'GET', credentials: 'include',
                    headers: { 'Accept': 'application/json', 'platform': 'app', 'tenant-id': '1' }
                }).then(r => r.text());
            },
            rawBasicInfo: function(iccid) {
                return fetch(API_BASE + '/basicInfo?iccid=' + encodeURIComponent(iccid), {
                    method: 'GET', credentials: 'include',
                    headers: { 'Accept': 'application/json', 'platform': 'app', 'tenant-id': '1' }
                }).then(r => r.text());
            },
            rawDiagnose: function(iccid) {
                return fetch(API_BASE + '/intelligentDiagnosis?iccid=' + encodeURIComponent(iccid), {
                    method: 'GET', credentials: 'include',
                    headers: { 'Accept': 'application/json', 'platform': 'app', 'tenant-id': '1' }
                }).then(r => r.text());
            },
            rawRebind: function(iccid, imei) {
                return fetch(API_BASE + '/operationCommit', {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json', 'platform': 'app', 'tenant-id': '1' },
                    body: JSON.stringify({ iccid: iccid, imei: imei, type: 'rebind' })
                }).then(r => r.text());
            },

            ready: function() {
                try { window.$BRIDGE_NAME && window.$BRIDGE_NAME.onPageReady(location.pathname); } catch(e){}
                return location.pathname;
            }
        };

        window.__ctwing = __ctwing;
        LOG('CTWing bridge injected (diagnostic + UI auto). api=' + API_BASE);
        try { __ctwing.ready(); } catch(e){}
    })();
    """.trimIndent()
}