package dev.example.autoreply.ctwing

/**
 * JS payload injected into the CTWing SPA WebView via evaluateJavascript.
 *
 * === STRATEGY (v6 — Vue service hijack, no UI automation) ===
 * 1. Find Vue 3 app instance via document.querySelector('#app').__vue_app__
 * 2. Walk component tree to find the axios/http service instance
 * 3. Call SPA's own API methods — sbu1 interceptor auto-encrypts/decrypts
 * 4. Responses stored in window.__ctwingRecon.apiResponses for sync read
 *
 * Because we use the SPA's own HTTP client (not raw fetch/XHR), the sbu1
 * encryption/decryption interceptor fires automatically. No passphrase
 * reverse-engineering needed, no UI simulation needed.
 */
object CtwingJsInjector {

    /** JS bridge name registered via addJavascriptInterface. */
    const val BRIDGE_NAME = "ctwingBridge"

    fun build(): String = """
    (function() {
        var API_BASE = 'https://tywlonestop.ctwing.cn:8081/webapp-font/admin-api/bpm/service-assistant';
        var BRIDGE_NAME = 'ctwingBridge';
        var LOG_TAG = '[CTWing-JS]';

        function LOG(msg) {
            try { console.log(LOG_TAG + ' ' + msg); } catch(e){}
        }

        // ============================================================
        // 0. GLOBAL STATE
        // ============================================================
        window.__ctwingRecon = window.__ctwingRecon || { apiResponses: [] };

        // ============================================================
        // 1. VUE APP DISCOVERY — find the SPA's HTTP service
        // ============================================================
        function discoverVueService() {
            var key = '__ctwing__vueService';
            if (window[key]) return window[key];

            var appEl = document.querySelector('#app');
            if (!appEl) { LOG('no #app'); return null; }
            var app = appEl.__vue_app__;
            if (!app) { LOG('no __vue_app__'); return null; }

            // --- Path 1: Pinia stores (most likely for Vue 3 SPA) ---
            try {
                var pinia = app.config.globalProperties['${"$"}pinia'];
                if (pinia && pinia._s) {
                    var storeMap = pinia._s;
                    var isMap = (storeMap instanceof Map);
                    var storeIds = isMap ? Array.from(storeMap.keys()) : Object.keys(storeMap);
                    for (var si = 0; si < storeIds.length; si++) {
                        var storeId = storeIds[si];
                        var store = isMap ? storeMap.get(storeId) : storeMap[storeId];
                        if (!store) continue;
                        // Check store's state/actions for axios-like objects
                        var state = store['${"$"}state'] || store.state || {};
                        for (var sk in state) {
                            var sv = state[sk];
                            if (sv && typeof sv === 'object' && typeof sv.get === 'function' && sv.defaults) {
                                LOG('found service in pinia store ' + storeId + '.state.' + sk);
                                window[key] = sv;
                                return sv;
                            }
                        }
                        // Also check the store itself (might have injected dollar-http)
                        if (store['${"$"}http'] && typeof store['${"$"}http'].get === 'function') {
                            LOG('found service in pinia store ' + storeId + '.dollar-http');
                            window[key] = store['${"$"}http'];
                            return store['${"$"}http'];
                        }
                        if (store.http && typeof store.http.get === 'function') {
                            LOG('found service in pinia store ' + storeId + '.http');
                            window[key] = store.http;
                            return store.http;
                        }
                        if (store.api && typeof store.api.get === 'function') {
                            LOG('found service in pinia store ' + storeId + '.api');
                            window[key] = store.api;
                            return store.api;
                        }
                        if (store.request && typeof store.request.get === 'function') {
                            LOG('found service in pinia store ' + storeId + '.request');
                            window[key] = store.request;
                            return store.request;
                        }
                    }
                }
            } catch(e) { LOG('pinia scan err: ' + e.message); }

            // --- Path 2: Walk component tree (BFS) ---
            var root = app._instance;
            if (root) {
                var visited = new Set();
                var queue = [root];
                while (queue.length > 0) {
                    var comp = queue.shift();
                    if (!comp || visited.has(comp)) continue;
                    visited.add(comp);
                    var ss = comp.setupState;
                    if (ss) {
                        for (var k in ss) {
                            var v = ss[k];
                            if (v && typeof v === 'object' && typeof v.get === 'function' && v.defaults) {
                                LOG('found service in setupState.' + k);
                                window[key] = v;
                                return v;
                            }
                        }
                    }
                    // enqueue children
                    var st = comp.subTree;
                    if (st) {
                        if (st.component) queue.push(st.component);
                        var children = st.children || st.dynamicChildren;
                        if (children) {
                            for (var ci = 0; ci < children.length; ci++) {
                                if (children[ci] && children[ci].component) queue.push(children[ci].component);
                            }
                        }
                    }
                }
            }

            LOG('no Vue HTTP service found');
            return null;
        }

        // ============================================================
        // 2. PASSIVE RESPONSE CAPTURE — getter-only hook on XHR response.
        //    Does NOT wrap/replace any method, so it cannot interfere with
        //    sbu1's encryption. Only observes the DECRYPTED responseText
        //    after sbu1 has written it, storing a copy for readApiResponses.
        // ============================================================

        (function() {
            try {
                var origRespDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
                if (origRespDesc && origRespDesc.get) {
                    var origGetter = origRespDesc.get;
                    Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                        get: function() {
                            var v = origGetter.call(this);
                            try {
                                if (v && typeof v === 'string' && v.length > 0 && v.length < 8000) {
                                    var url = this.__ctwingUrl || '';
                                    if (url.indexOf('ctwing.cn') >= 0) {
                                        window.__ctwingRecon._lastXhrBody = v;
                                        window.__ctwingRecon._lastXhrUrl = url;
                                        window.__ctwingRecon._lastXhrAt = Date.now();
                                    }
                                }
                            } catch(e) {}
                            return v;
                        },
                        set: origRespDesc.set,
                        configurable: true
                    });
                }
                // Track URL via open (safe — just records, doesn't wrap behavior)
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__ctwingUrl = String(url);
                    return origOpen.apply(this, arguments);
                };
            } catch(e) {}
        })();

        // ============================================================
        // 3. PUBLIC API (called via evaluateJavascript)
        // ============================================================

        var __ctwing = {

            // Discover Vue HTTP service
            discover: function() {
                var svc = discoverVueService();
                var appEl = document.querySelector('#app');
                var app = appEl ? appEl.__vue_app__ : null;
                var root = app ? app._instance : null;

                // Dump structure for diagnosis
                var piniaIds = [];
                var userInfoDump = null;
                try {
                    var gps = app && app.config && app.config.globalProperties;
                    var pinia = gps ? (gps['${"$"}pinia'] || gps.pinia) : null;
                    if (pinia && pinia._s) {
                        var isMap = (pinia._s instanceof Map);
                        var sids = isMap ? Array.from(pinia._s.keys()) : Object.keys(pinia._s);
                        piniaIds.push({ storeIds: sids });
                        for (var i2 = 0; i2 < Math.min(sids.length, 8); i2++) {
                            var sid = sids[i2];
                            var s = isMap ? pinia._s.get(sid) : pinia._s[sid];
                            if (s) {
                                var stateKeys = s['${"$"}state'] ? Object.keys(s['${"$"}state']).slice(0, 10) : [];
                                var otherKeys = Object.keys(s).filter(function(k) { return k[0] !== '${"$"}' && k !== '_hotUpdate'; }).slice(0, 10);
                                piniaIds.push({ id: String(sid), stateKeys: stateKeys, keys: otherKeys });
                                // Dump user.info structure
                                if (String(sid) === 'user' && s['${"$"}state'] && s['${"$"}state'].info) {
                                    var ui = s['${"$"}state'].info;
                                    userInfoDump = {
                                        type: typeof ui,
                                        keys: Object.keys(ui).slice(0, 30),
                                        isAxios: !!(ui.defaults && typeof ui.get === 'function'),
                                        baseURL: ui.defaults ? ui.defaults.baseURL : null
                                    };
                                }
                            }
                        }
                    }
                } catch(e) { piniaIds = ['err:'+e.message]; }

                // Dump window-level axios/http candidates
                var winHttp = [];
                try {
                    for (var wk in window) {
                        var wv = window[wk];
                        if (wv && typeof wv === 'object' && typeof wv.get === 'function' && wv.defaults) {
                            winHttp.push({ name: wk, baseURL: wv.defaults.baseURL || null });
                        }
                    }
                } catch(e) { winHttp = ['err:'+e.message]; }

                // Walk Vue component tree and list component names + exposed methods
                var componentTree = [];
                try {
                    var visited = new Set();
                    var queue = [{ comp: root, depth: 0 }];
                    while (queue.length > 0 && componentTree.length < 30) {
                        var item = queue.shift();
                        var comp = item.comp;
                        if (!comp || visited.has(comp)) continue;
                        visited.add(comp);
                        var name = (comp.type && (comp.type.name || comp.type.__name)) || 
                                   (comp.type && comp.type.__file) || 
                                   (comp.props && comp.props.class) || 
                                   'anonymous';
                        var methods = [];
                        if (comp.setupState) {
                            for (var mk in comp.setupState) {
                                if (typeof comp.setupState[mk] === 'function') methods.push(mk);
                            }
                        }
                        if (comp.type && comp.type.methods) {
                            for (var mm in comp.type.methods) methods.push(mm);
                        }
                        componentTree.push({
                            depth: item.depth,
                            name: String(name).substring(0, 60),
                            methods: methods.slice(0, 10)
                        });
                        // enqueue children
                        var st = comp.subTree;
                        if (st) {
                            if (st.component) queue.push({ comp: st.component, depth: item.depth + 1 });
                            var children = st.children || st.dynamicChildren;
                            if (children) {
                                for (var ci = 0; ci < children.length; ci++) {
                                    if (children[ci] && children[ci].component)
                                        queue.push({ comp: children[ci].component, depth: item.depth + 1 });
                                }
                            }
                        }
                    }
                } catch(e) { componentTree = ['err:'+e.message]; }

                // Dump Vue Router routes for finding query page
                var routerInfo = { currentRoute: null, routes: [] };
                try {
                    var router = gps['${"$"}router'];
                    if (router) {
                        routerInfo.currentRoute = router.currentRoute && router.currentRoute.value ? {
                            path: router.currentRoute.value.path,
                            name: router.currentRoute.value.name,
                            query: router.currentRoute.value.query
                        } : null;
                        var routes = router.options && router.options.routes ? router.options.routes : (router.getRoutes ? router.getRoutes() : []);
                        routerInfo.routes = routes.map(function(r) {
                            return { path: r.path, name: r.name, meta: r.meta ? Object.keys(r.meta) : [] };
                        }).slice(0, 20);
                    }
                } catch(e) { routerInfo = { error: e.message }; }

                var info = {
                    serviceFound: !!svc,
                    router: routerInfo,
                    vueVersion: 3,
                    serviceType: svc ? (svc.defaults && svc.defaults.baseURL ? 'axios' : 'custom') : 'none',
                    baseURL: svc && svc.defaults ? svc.defaults.baseURL : null,
                    piniaStores: piniaIds,
                    userInfo: userInfoDump,
                    windowHttp: winHttp,
                    componentTree: componentTree,
                    rootKeys: root && root.setupState ? Object.keys(root.setupState).slice(0, 40) : [],
                    gpKeys: app && app.config && app.config.globalProperties ? Object.keys(app.config.globalProperties).slice(0, 40) : []
                };
                return JSON.stringify(info);
            },

            // WARNING: Kotlin raw string — DO NOT add dollar-sign inside JS comments or strings
            // without proper escaping. All ${"$"}xxx are intentional JS dollar-identifiers.

            // ---- Query card — fetch(), token-expired handled by Kotlin rebuild ----
            queryCard: function(iccid) {
                window.__ctwingRecon._queryStatus = 'starting';
                window.__ctwingRecon._queryRaw = null;
                window.__dshResult = '';
                var id = iccid;
                if (id.length === 20 && id.charAt(0) === '8') id = id.substring(0, 19);
                var type = 'iccid';
                if (/^1[0-9]{10,12}$/.test(id)) type = 'msisdn';
                else if (/^\d{15}$/.test(id)) type = 'imsi';

                var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                var base = location.origin || 'https://tywlonestop.ctwing.cn:8081';
                var url = base + '/webapp-font/admin-api/bpm/service-assistant/querySimBaseInfo?type=' + encodeURIComponent(type) + '&id=' + encodeURIComponent(id);
                window.__dshResult = 'fetching:' + url.substring(url.indexOf('?')+1, 80);
                fetch(url, {headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}})
                    .then(function(r){return r.text();})
                    .then(function(body){
                        window.__ctwingRecon._queryRaw = body;
                        window.__ctwingRecon._queryStatus = 'query-ok';
                        window.__dshResult = body;
                    }).catch(function(e){
                        var err = JSON.stringify({error:true,msg:e.message||''});
                        window.__ctwingRecon._queryRaw = err;
                        window.__ctwingRecon._queryStatus = 'query-err';
                        window.__dshResult = err;
                    });
                return JSON.stringify({dispatched:true,type:type,id:id});
            },

            // ---- Diagnose card — same pattern ----
            diagnoseCard: function(iccid) {
                window.__ctwingRecon._queryStatus = 'starting';
                window.__ctwingRecon._diagRaw = null;
                window.__dshResult = '';
                var id = iccid;
                if (id.length === 20 && id.charAt(0) === '8') id = id.substring(0, 19);
                var type = 'iccid';
                if (/^1[0-9]{10,12}$/.test(id)) type = 'msisdn';
                else if (/^\d{15}$/.test(id)) type = 'imsi';

                function doFetch() {
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var base = location.origin || 'https://tywlonestop.ctwing.cn:8081';
                    var url = base + '/webapp-font/admin-api/bpm/service-assistant/intelligentDiagnosis?type=' + encodeURIComponent(type) + '&id=' + encodeURIComponent(id);
                    fetch(url, {headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}})
                        .then(function(r){return r.text();})
                        .then(function(body){
                            var expired = body.trim().startsWith('<!DOCTYPE') ||
                                          body.trim().startsWith('<html') ||
                                          (body.indexOf('账号未登录') >= 0) ||
                                          (body.indexOf('"code":401') >= 0);
                            if (expired) {
                                window.__ctwingRecon._queryStatus = 'token-expired';
                                return;
                            }
                            window.__ctwingRecon._diagRaw = body;
                            window.__dshResult = body;  // ← Kotlin readApiResponses reads this
                            window.__ctwingRecon._queryStatus = 'diagnose-ok';
                            try { window['ctwingBridge'].onApiCapture(body); } catch(e){}
                        }).catch(function(e){
                            var err = JSON.stringify({error:true,msg:e.message||''});
                            window.__ctwingRecon._diagRaw = err;
                            window.__dshResult = err;
                            window.__ctwingRecon._queryStatus = 'diagnose-err';
                            try { window['ctwingBridge'].onApiCapture(err); } catch(e2){}
                        });
                }
                doFetch();
                window.__ctwingRecon._queryStatus = 'diagnose-sent';
                return JSON.stringify({dispatched:true,type:type,id:id});
            },

            // ---- Query basicInfo (for rebind: get bindImei/orderNumber/sessionId) ----
            queryBasicInfo: function(cardNo, type) {
                window.__ctwingRecon._queryStatus = 'starting';
                window.__dshResult = '';
                var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                var base = location.origin || 'https://tywlonestop.ctwing.cn:8081';
                var url = base + '/webapp-font/admin-api/bpm/service-assistant/basicInfo?type=' + encodeURIComponent(type) + '&id=' + encodeURIComponent(cardNo) + '&isFromWeb=true&userId=199';
                fetch(url, {headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}})
                    .then(function(r){return r.text();})
                    .then(function(body){
                        window.__dshResult = body;
                        window.__ctwingRecon._queryStatus = 'basicInfo-ok';
                    }).catch(function(e){
                        window.__dshResult = JSON.stringify({error:true,msg:e.message||''});
                        window.__ctwingRecon._queryStatus = 'basicInfo-err';
                    });
                return 'sent';
            },

            // ---- POST operationCommit (for rebind: JKCB) ----
            operationCommit: function(payloadStr) {
                window.__ctwingRecon._queryStatus = 'starting';
                window.__dshResult = '';
                try {
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var base = location.origin || 'https://tywlonestop.ctwing.cn:8081';
                    var url = base + '/webapp-font/admin-api/bpm/service-assistant/operationCommit';
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', url, true);
                    xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8');
                    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
                    xhr.onreadystatechange = function(){
                        if (xhr.readyState === 4) {
                            window.__dshResult = JSON.stringify({status:xhr.status, body:xhr.responseText});
                            window.__ctwingRecon._queryStatus = 'operationCommit-ok';
                        }
                    };
                    xhr.send(payloadStr);
                } catch(e) {
                    window.__dshResult = JSON.stringify({error:true,msg:e.message||''});
                    window.__ctwingRecon._queryStatus = 'operationCommit-err';
                }
                return 'sent';
            },

            // ---- Walk the FULL Vue component tree from #app root, collecting all
            //    objects that look like diagnosis results (project+conclusion) ----
            _collectDiagResults: function() {
                var lines = [];
                var structDump = [];
                try {
                    var root = document.querySelector('#app')?.__vue_app__?._instance;
                    if (!root) return ['no-root'];
                    walkComp(root, new Set(), 0);
                } catch(e) { structDump.push('err:' + e.message); }
                if (lines.length === 0 && structDump.length > 0) return structDump.slice(0, 30);
                return lines;

                function walkComp(comp, visited, depth) {
                    if (!comp || visited.has(comp) || depth > 30) return;
                    visited.add(comp);
                    var ss = comp.setupState;
                    if (ss) {
                        for (var k in ss) {
                            var v = ss[k];
                            if (!v || typeof v !== 'object' || Array.isArray(v)) continue;
                            // Check all objects for diagnosis-like fields
                            var hasDiag = false;
                            try {
                                if (typeof v.project === 'string' && v.project.length > 0 && v.project.length <= 40) hasDiag = true;
                                else if (typeof v.conclusion === 'string' && v.conclusion.length > 0) hasDiag = true;
                                else if (typeof v.status === 'string' && /正常|异常|在用|已订购|未订购/.test(v.status)) hasDiag = true;
                            } catch(e) {}
                            if (hasDiag) {
                                var proj = v.project || v.name || v.title || k;
                                var concl = v.conclusion || v.status || v.result || '';
                                lines.push(String(proj).trim() + '=' + String(concl).trim());
                            }
                            // Dump shape for interesting objects
                            try {
                                var keys = Object.keys(v).slice(0, 10).join(',');
                                if (keys.length > 0 && keys.length < 80 && /conclu|project|status|result|name|结论|诊断/.test(keys + JSON.stringify(v).substring(0,60))) {
                                    structDump.push('[' + k + ']' + keys);
                                }
                            } catch(e) {}
                        }
                    }
                    // Also check props
                    var props = comp.props;
                    if (props) {
                        for (var pk in props) {
                            try {
                                var pv = props[pk];
                                if (pv && typeof pv === 'object' && !Array.isArray(pv) && Object.keys(pv).length > 2 && Object.keys(pv).length < 20) {
                                    structDump.push('props.' + pk + '={' + Object.keys(pv).slice(0,10).join(',') + '}');
                                }
                            } catch(e) {}
                        }
                    }
                    // Recurse
                    var st = comp.subTree;
                    if (st) {
                        if (st.component) walkComp(st.component, visited, depth + 1);
                        var kids = st.children || st.dynamicChildren;
                        if (kids) for (var ki = 0; ki < kids.length; ki++) {
                            if (kids[ki] && kids[ki].component) walkComp(kids[ki].component, visited, depth + 1);
                        }
                    }
                }
            },

            /** Sync read. If _queryRaw or _diagRaw is set (fetch-based call), parse
             *  it on the JS side to avoid nested quote-escaping problems. */
            readApiResponses: function() {
                var d = window.__ctwingRecon || {};
                // If fetch-based query or diagnose succeeded, return parsed result
                var raw = d._queryRaw || d._diagRaw;
                if (raw) {
                    try {
                        var parsed = JSON.parse(raw);
                        var result = { q: d._queryStatus, ok: true, data: parsed, dom: '' };
                        return JSON.stringify(result);
                    } catch(e) {
                        return JSON.stringify({ q: d._queryStatus, ok: false, raw: raw.substring(0, 500), dom: '' });
                    }
                }
                // No fetch result yet — return status only (so Kotlin knows it's pending)
                return JSON.stringify({ q: d._queryStatus || 'pending', ok: false, raw: null, dom: '' });
            },

            extractCredentials: function() {
                var creds = { token: '', cookie: document.cookie||'', localStorageKeys:[], sessionStorageKeys:[] };
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        var v = localStorage.getItem(k);
                        creds.localStorageKeys.push({k:k, v:(v||'').substring(0,200)});
                    }
                } catch(e) { creds.lsErr = e.message; }
                try {
                    for (var j = 0; j < sessionStorage.length; j++) {
                        creds.sessionStorageKeys.push({
                            k: sessionStorage.key(j),
                            v: (sessionStorage.getItem(sessionStorage.key(j))||'').substring(0,200)
                        });
                    }
                } catch(e) { creds.ssErr = e.message; }
                return JSON.stringify(creds);
            },

            recon: function() {
                var svc = discoverVueService();
                return JSON.stringify({
                    serviceFound: !!svc,
                    vueVersion: (document.querySelector('#app')||{}).__vue_app__ ? 3 : 0,
                    responses: (window.__ctwingRecon.apiResponses||[]).length,
                    cryptoConfig: !!(window.__ctwingRecon.cryptoConfig)
                });
            },

            uiDump: function() {
                var out = [];
                out.push('TITLE: ' + document.title);
                out.push('URL: ' + location.href);

                var tabs = document.querySelectorAll('.van-tab, [role="tab"], .tab-item, .el-tabs__item, .van-tabbar-item');
                out.push('\n=== TABS (' + tabs.length + ') ===');
                for (var ti = 0; ti < tabs.length; ti++) {
                    var t = tabs[ti];
                    var tb = t.getBoundingClientRect();
                    out.push('  [' + ti + '] text="' + (t.textContent||'').trim() + '" class="' + (t.className||'') + '" visible=' + (tb.width > 0) + ' rect=' + JSON.stringify({x:Math.round(tb.x),y:Math.round(tb.y),w:Math.round(tb.width),h:Math.round(tb.height)}));
                }

                var inputs = document.querySelectorAll('input:not([type="hidden"]), .van-field__control, textarea');
                out.push('\n=== INPUTS (' + inputs.length + ') ===');
                for (var ii = 0; ii < inputs.length; ii++) {
                    var inp = inputs[ii];
                    var ib = inp.getBoundingClientRect();
                    out.push('  [' + ii + '] type=' + (inp.type||'text') + ' placeholder="' + (inp.placeholder||'') + '" visible=' + (ib.width > 0) + ' rect=' + JSON.stringify({x:Math.round(ib.x),y:Math.round(ib.y),w:Math.round(ib.width),h:Math.round(ib.height)}));
                }

                var btns = document.querySelectorAll('button, [role="button"], .van-button, .el-button');
                out.push('\n=== BUTTONS (' + btns.length + ') ===');
                for (var bi = 0; bi < btns.length; bi++) {
                    var b = btns[bi];
                    var bb = b.getBoundingClientRect();
                    out.push('  [' + bi + '] text="' + (b.textContent||'').trim().substring(0,30) + '" visible=' + (bb.width > 0) + ' rect=' + JSON.stringify({x:Math.round(bb.x),y:Math.round(bb.y),w:Math.round(bb.width),h:Math.round(bb.height)}));
                }

                var allEls = document.querySelectorAll('*');
                var matches = [];
                for (var ei = 0; ei < allEls.length; ei++) {
                    var el = allEls[ei];
                    var t = (el.textContent||'').trim();
                    if (t === '智能快查' || t === '快查' || t === '卡查询' || t === '查询' || t === '工单' || t === '我的' || t === '首页' || t === '诊断' || t === '重绑') {
                        var eb = el.getBoundingClientRect();
                        if (eb.width > 0 && eb.height > 0 && eb.y < 3000) {
                            matches.push({ text: t, tag: el.tagName, cls: (el.className||'').substring(0,50), x: Math.round(eb.x), y: Math.round(eb.y), w: Math.round(eb.width), h: Math.round(eb.height) });
                        }
                    }
                }
                out.push('\n=== TEXT MATCHES ===');
                out.push(JSON.stringify(matches));

                return out.join('\n');
            },

            // Extract auth token + bond cookie for native HTTP fallback
            getAuthData: function() {
                var token = '';
                try { token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || ''; } catch(e){}
                var bond = '';
                try { bond = (document.cookie.match(/ctl-dync-ct-bond=([^;]+)/)||[])[1] || ''; } catch(e){}
                var ls = {};
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        if (k) ls[k] = (localStorage.getItem(k)||'').substring(0, 200);
                    }
                } catch(e){}
                try {
                    if (!token) token = sessionStorage.getItem('ACCESS_TOKEN') || localStorage.getItem('ACCESS_TOKEN') || '';
                } catch(e){}
                try { window['ctwingBridge'] && window['ctwingBridge'].onAuthData(token, bond); } catch(e){}
                return JSON.stringify({ token: token, bond: bond, cookie: document.cookie || '', ls: ls });
            },

            reconReport: function() { return JSON.stringify(window.__ctwingRecon); },
            dump: function() { return (document.documentElement||{}).outerHTML||''; },

            ready: function() {
                try { window[BRIDGE_NAME] && window[BRIDGE_NAME].onPageReady(location.pathname); } catch(e){}
                discoverVueService(); // pre-cache
                // Auto-extract token + bond for native HTTP — retry up to 15s (SPA needs time for 免密登录)
                function pushAuth() { try { __ctwing.getAuthData(); } catch(e2){} }
                pushAuth();
                setTimeout(pushAuth, 3000);
                setTimeout(pushAuth, 6000);
                setTimeout(pushAuth, 9000);
                setTimeout(pushAuth, 15000);
                return location.pathname;
            }
        };

        window.__ctwing = __ctwing;
        LOG('CTWing bridge v6 injected (Vue hijack). ready.');
        try { __ctwing.ready(); } catch(e){}
    })();
    """.trimIndent()
}