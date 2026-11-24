/* X SATURN – حارس النوافذ المنبثقة. يُحقن قبل سكربتات الصفحة (document-start).
 * __CONFIG__ يُستبدل بكائن {on:boolean, allowed:[sites]} */
(function () {
  'use strict';
  var CFG = __CONFIG__;
  var host;
  try {
    if (window.__xsGuardInstalled) return;
    if (!CFG || !CFG.on) return;
    host = (location.hostname || '').toLowerCase();
    var allowed = CFG.allowed || [];
    for (var i = 0; i < allowed.length; i++) {
      var a = allowed[i];
      if (host === a || host.slice(-(a.length + 1)) === '.' + a) return;
    }
    Object.defineProperty(window, '__xsGuardInstalled', { value: true });
  } catch (e) { return; }

  var blocked = 0;
  try {
    // قراءة وتصفير: تقرؤها طبقة التطبيق لتسجيل الحجب الحقيقي
    Object.defineProperty(window, '__xsPopCount', {
      get: function () { var c = blocked; blocked = 0; return c; }
    });
  } catch (e) {}

  function userActive() {
    try { return navigator.userActivation ? navigator.userActivation.isActive : null; }
    catch (e) { return null; }
  }

  function lastTwo(h) { var p = h.split('.'); return p.length <= 2 ? h : p.slice(-2).join('.'); }
  function sameSite(a, b) {
    return a === b || lastTwo(a) === lastTwo(b);
  }

  var lastTarget = null, lastTime = 0;

  // ---- اختطاف النقرات: طبقة شفافة/فارغة تغطي الشاشة وتفتح إعلاناً عند أي لمسة ----
  function coverage(el) {
    var r = el.getBoundingClientRect();
    var w = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
    var h = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
    return (w * h) / Math.max(1, window.innerWidth * window.innerHeight);
  }
  function hijackOverlay(el) {
    try {
      if (el && el.closest && el.closest('[data-xs-ov]')) return el.closest('[data-xs-ov]');
      var n = el, d = 0;
      while (n && n.nodeType === 1 && n !== document.body && n !== document.documentElement && d < 6) {
        var cs = window.getComputedStyle(n);
        if ((cs.position === 'fixed' || cs.position === 'absolute') && coverage(n) >= 0.5) {
          var empty = !(n.innerText || '').trim() &&
            !n.querySelector('img,video,iframe,canvas,a,button,input,svg');
          if (parseFloat(cs.opacity) < 0.1 || empty) return n;
        }
        n = n.parentElement; d++;
      }
    } catch (e) {}
    return null;
  }
  function neutralize(e) {
    var o = hijackOverlay(e.target);
    if (!o) return false;
    if (!o.hasAttribute('data-xs-ov')) { o.setAttribute('data-xs-ov', '1'); blocked++; }
    o.style.setProperty('display', 'none', 'important');
    return true;
  }
  window.addEventListener('pointerdown', neutralize, true);
  window.addEventListener('touchstart', neutralize, { capture: true, passive: true });
  window.addEventListener('click', function (e) {
    if (neutralize(e)) {
      e.stopImmediatePropagation(); e.preventDefault();
      lastTarget = null; lastTime = 0;
      return;
    }
    lastTarget = e.target; lastTime = Date.now();
  }, true);

  function interactive(el) {
    try {
      var n = el, depth = 0;
      while (n && n.nodeType === 1 && depth < 8) {
        var tag = n.tagName;
        if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'SELECT' ||
            tag === 'TEXTAREA' || tag === 'LABEL' || tag === 'SUMMARY' || tag === 'AREA') return true;
        if (n.hasAttribute('role') || n.hasAttribute('onclick') || n.hasAttribute('tabindex')) return true;
        if (window.getComputedStyle(n).cursor === 'pointer') return true;
        n = n.parentElement; depth++;
      }
    } catch (e) { return true; }
    return false;
  }

  function parse(u) {
    try {
      var t = new URL(String(u == null ? '' : u), location.href);
      return /^https?:$/.test(t.protocol) ? t : null;
    } catch (e) { return null; }
  }

  function crossSite(t) { return !!t && !sameSite(t.hostname.toLowerCase(), host); }

  // نقرة حديثة على عنصر تفاعلي حقيقي؟ (وإلا فهي غالباً اختطاف نقرات)
  function genuineClick() { return (Date.now() - lastTime) < 1500 && interactive(lastTarget); }

  // منع التنقل البرمجي إلى موقع آخر بلا نقرة حقيقية على عنصر تفاعلي (Navigation API)
  try {
    if (window.navigation && window.navigation.addEventListener) {
      window.navigation.addEventListener('navigate', function (e) {
        try {
          if (e.userInitiated || !e.cancelable || e.hashChange || e.downloadRequest != null) return;
          if (e.navigationType === 'traverse' || e.navigationType === 'reload') return;
          var t = parse(e.destination && e.destination.url);
          if (crossSite(t) && !genuineClick()) { e.preventDefault(); blocked++; }
        } catch (x) {}
      });
    }
  } catch (e) {}

  // ---- جدار «أنت تستخدم مانع إعلانات»: حذف النافذة وإعادة التمرير ----
  var WALL_RE = new RegExp(
    'ad[\\s_-]?block|ads[\\s_-]?block|block(?:ing)?\\s+(?:the\\s+)?ads|disable\\s+(?:your\\s+)?ad|' +
    'ad[\\s_-]?blocker|anti[\\s_-]?adblock|whitelist\\s+(?:us|this|our|the)|' +
    'مانع\\s*(?:ال)?[اأإ]علانات|حاجب\\s*(?:ال)?[اأإ]علانات|(?:إيقاف|ايقاف|تعطيل|أوقف|اوقف|عطّل|عطل)\\s*(?:تشغيل\\s*)?مانع|' +
    'bloqueador\\s+de\\s+anuncios|bloqueur\\s+de\\s+pub', 'i');
  var LOCK_RE = /modal-open|no-?scroll|overflow-?hidden|scroll-?lock|lock-?scroll|disable-?scroll|adblock|fixed-?body|stop-?scrolling/i;
  var BACKDROP_RE = /backdrop|overlay|modal|mask|shade|dimmer|blocker|wall|lightbox|popup/i;

  function isFloating(cs) { return cs.position === 'fixed' || cs.position === 'absolute' || cs.position === 'sticky'; }

  function hideWall(el) {
    el.setAttribute('data-xs-wall', '1');
    el.style.setProperty('display', 'none', 'important');
    el.style.setProperty('pointer-events', 'none', 'important');
    blocked++;
  }

  function unlockScroll() {
    try {
      var roots = [document.documentElement, document.body];
      for (var i = 0; i < roots.length; i++) {
        var r = roots[i];
        if (!r) continue;
        var cs = window.getComputedStyle(r);
        if (cs.overflow === 'hidden' || cs.overflowY === 'hidden') {
          r.style.setProperty('overflow', 'auto', 'important');
          r.style.setProperty('overflow-y', 'auto', 'important');
        }
        if (r === document.body && cs.position === 'fixed') r.style.setProperty('position', 'static', 'important');
        if (cs.filter && cs.filter !== 'none' && /blur/.test(cs.filter)) r.style.setProperty('filter', 'none', 'important');
        var cl = r.classList;
        for (var j = cl.length - 1; j >= 0; j--) { if (LOCK_RE.test(cl[j])) cl.remove(cl[j]); }
      }
    } catch (e) {}
  }

  function scanWalls() {
    try {
      if (!document.body) return;
      var vw = window.innerWidth, vh = window.innerHeight, found = false;
      var list = document.body.querySelectorAll(
        'div,section,aside,dialog,form,[role=dialog],[role=alertdialog]');
      var limit = Math.min(list.length, 2500);
      for (var i = 0; i < limit; i++) {
        var el = list[i];
        if (el.hasAttribute('data-xs-wall')) continue;
        var t = el.textContent;
        if (!t || t.length < 15 || t.length > 2500 || !WALL_RE.test(t)) continue;
        var cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        if (!isFloating(cs)) continue;
        // اصعد إلى أعلى حاوية عائمة (الخلفية الداكنة والنافذة معاً)
        var top = el, n = el.parentElement;
        while (n && n !== document.body && n !== document.documentElement) {
          if (isFloating(window.getComputedStyle(n))) top = n;
          n = n.parentElement;
        }
        hideWall(top);
        found = true;
      }
      if (found) {
        // خلفيات معتمة متبقية بلا نص
        var all = document.body.querySelectorAll('div,section,aside');
        var lim2 = Math.min(all.length, 2500);
        for (var k = 0; k < lim2; k++) {
          var b = all[k];
          if (b.hasAttribute('data-xs-wall')) continue;
          var bs = window.getComputedStyle(b);
          if (!isFloating(bs) || bs.display === 'none') continue;
          if (coverage(b) < 0.85) continue;
          if ((b.innerText || '').trim().length > 0) continue;
          if (BACKDROP_RE.test((b.className && b.className.toString ? b.className.toString() : '') + ' ' + (b.id || '')) ||
              parseFloat(bs.opacity) < 1 || /rgba|rgb/.test(bs.backgroundColor)) hideWall(b);
        }
        unlockScroll();
      }
    } catch (e) {}
  }

  var scanTimer = null;
  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(function () { scanTimer = null; scanWalls(); }, 300);
  }
  try {
    var mo = new MutationObserver(scheduleScan);
    mo.observe(document.documentElement, { childList: true, subtree: true });
    document.addEventListener('DOMContentLoaded', scheduleScan);
    window.addEventListener('load', scheduleScan);
    setTimeout(scanWalls, 1000); setTimeout(scanWalls, 2500); setTimeout(scanWalls, 5000);
  } catch (e) {}

  var realOpen = window.open;
  window.open = function (url) {
    try {
      var active = userActive();
      var cross = crossSite(parse(url));
      if (active === false || (cross && !genuineClick())) { blocked++; return null; }
    } catch (e) {}
    return realOpen.apply(this, arguments);
  };

  try {
    var realClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try {
        var t = parse(this.href);
        if (crossSite(t)) {
          var tgt = (this.getAttribute('target') || '').toLowerCase();
          var opensNew = tgt !== '' && tgt !== '_self' && tgt !== '_top' && tgt !== '_parent';
          if ((!this.isConnected || opensNew) && !genuineClick()) { blocked++; return; }
        }
      } catch (e) {}
      return realClick.apply(this, arguments);
    };
  } catch (e) {}

  // =====================================================================
  // مقاومة كشف مانع الإعلانات (anti-adblock)
  // =====================================================================

  // 1) مكتبات الكشف الشهيرة: نعرّف نسخة وهمية تقول دائماً "لا يوجد مانع"
  try {
    var FakeABD = function () {};
    FakeABD.prototype = {
      onDetected: function () { return this; },
      onNotDetected: function (fn) { if (typeof fn === 'function') setTimeout(fn, 0); return this; },
      on: function (detected, fn) {
        if (!detected && typeof fn === 'function') setTimeout(fn, 0);
        return this;
      },
      check: function () { return true; },
      emitEvent: function () { return this; },
      clearEvent: function () { return this; },
      setOption: function () { return this; }
    };
    var names = ['fuckAdBlock', 'blockAdBlock', 'sniffAdBlock'];
    for (var ni = 0; ni < names.length; ni++) {
      try {
        Object.defineProperty(window, names[ni], {
          value: new FakeABD(), writable: true, configurable: true
        });
      } catch (e) {}
    }
    var ctors = ['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'];
    for (var ci = 0; ci < ctors.length; ci++) {
      try {
        Object.defineProperty(window, ctors[ci], {
          value: FakeABD, writable: true, configurable: true
        });
      } catch (e) {}
    }
    if (!window.adsbygoogle) { window.adsbygoogle = []; }
    try { window.adsbygoogle.loaded = true; } catch (e) {}
    try { if (window.canRunAds === undefined) window.canRunAds = true; } catch (e) {}
  } catch (e) {}

  // 2) العناصر الطُّعم (bait): تبدو للصفحة غير مخفية وذات حجم طبيعي
  var BAIT = /(^|[\s_-])(ads?|adsbox|adbox|adsense|adbanner|advert(isement)?s?|textad|banner[_-]?ads?|adslot|ad[_-]?slot|ad[_-]?placeholder|pub[_-]?\d+|sponsor(s|ed)?)([\s_-]|$|\d)/i;
  function isBait(el) {
    try {
      if (!el || el.nodeType !== 1) return false;
      var c = typeof el.className === 'string' ? el.className : '';
      return BAIT.test(c + ' ' + (el.id || ''));
    } catch (e) { return false; }
  }
  function patchGetter(proto, prop, fake) {
    try {
      var o = proto, d = null;
      while (o && !(d = Object.getOwnPropertyDescriptor(o, prop))) o = Object.getPrototypeOf(o);
      if (!d || !d.get) return;
      var orig = d.get;
      Object.defineProperty(o, prop, {
        configurable: true, enumerable: d.enumerable,
        get: function () {
          var v = orig.call(this);
          return (!v && isBait(this)) ? fake(this) : v;
        }
      });
    } catch (e) {}
  }
  patchGetter(HTMLElement.prototype, 'offsetHeight', function () { return 250; });
  patchGetter(HTMLElement.prototype, 'offsetWidth', function () { return 300; });
  patchGetter(Element.prototype, 'clientHeight', function () { return 250; });
  patchGetter(Element.prototype, 'clientWidth', function () { return 300; });
  patchGetter(HTMLElement.prototype, 'offsetParent', function () { return document.body; });

  try {
    var realGCS = window.getComputedStyle;
    window.getComputedStyle = function (el) {
      var cs = realGCS.apply(window, arguments);
      try {
        if (isBait(el)) {
          return new Proxy(cs, {
            get: function (t, k) {
              if (k === 'display') return 'block';
              if (k === 'visibility') return 'visible';
              if (k === 'getPropertyValue') {
                return function (n) {
                  if (n === 'display') return 'block';
                  if (n === 'visibility') return 'visible';
                  return t.getPropertyValue(n);
                };
              }
              var v = t[k];
              return typeof v === 'function' ? v.bind(t) : v;
            }
          });
        }
      } catch (e) {}
      return cs;
    };
  } catch (e) {}

  try {
    var realRect = Element.prototype.getBoundingClientRect;
    Element.prototype.getBoundingClientRect = function () {
      var r = realRect.call(this);
      try {
        if (r.width === 0 && r.height === 0 && isBait(this)) {
          return new DOMRect(r.x, r.y, 300, 250);
        }
      } catch (e) {}
      return r;
    };
  } catch (e) {}

  // 3) نافذة "أوقف مانع الإعلانات": نكتشفها بنصها وبكونها طبقة كبيرة فوق الصفحة ونزيلها
  var WALL = /ad[\s-]?block|ad[\s-]?blocker|adblocker|disable (your )?ad|turn off (your )?ad|whitelist (us|this)|مانع\s*(ال)?\s*(إ|ا)علانات|مانعات\s*(ال)?\s*(إ|ا)علانات|إيقاف\s*(تشغيل\s*)?مانع|تعطيل\s*مانع|أوقف(ه)?\s*مانع/i;
  var sweepTimer = null, sweepEnd = Date.now() + 40000;

  function unlockScroll() {
    try {
      var els = [document.documentElement, document.body];
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (!e) continue;
        e.style.setProperty('overflow', 'auto', 'important');
        e.style.setProperty('position', 'static', 'important');
        e.classList.remove('modal-open', 'no-scroll', 'noscroll', 'overflow-hidden', 'adblock', 'adblock-active');
      }
    } catch (x) {}
  }

  function sweepWalls() {
    sweepTimer = null;
    try {
      if (!document.body) return;
      var nodes = document.body.querySelectorAll('div,section,aside,dialog,form,[role="dialog"]');
      var limit = Math.min(nodes.length, 1500), removed = 0;
      for (var i = 0; i < limit; i++) {
        var el = nodes[i];
        if (el.hasAttribute('data-xs-wall')) continue;
        var cs = window.getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute' && cs.position !== 'sticky') continue;
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        var txt = el.textContent || '';
        if (txt.length > 2500 || !WALL.test(txt)) continue;
        var r = el.getBoundingClientRect();
        if (r.width * r.height < window.innerWidth * window.innerHeight * 0.12) continue;
        el.setAttribute('data-xs-wall', '1');
        el.style.setProperty('display', 'none', 'important');
        removed++;
        // الخلفية المعتمة التي تخلّفها النافذة عادةً
        var sib = el.parentElement ? el.parentElement.children : [];
        for (var j = 0; j < sib.length; j++) {
          var b = sib[j];
          if (b === el || b.hasAttribute('data-xs-wall')) continue;
          var bs = window.getComputedStyle(b);
          if ((bs.position === 'fixed') && !(b.textContent || '').trim() &&
              coverage(b) >= 0.8 && b.querySelector('img,video,iframe,canvas') === null) {
            b.setAttribute('data-xs-wall', '1');
            b.style.setProperty('display', 'none', 'important');
          }
        }
      }
      if (removed) { blocked += removed; unlockScroll(); }
    } catch (e) {}
  }

  function scheduleSweep() {
    if (sweepTimer !== null || Date.now() > sweepEnd) return;
    sweepTimer = setTimeout(sweepWalls, 250);
  }

  try {
    var startObserver = function () {
      if (!document.documentElement) return;
      new MutationObserver(scheduleSweep).observe(document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']
      });
      scheduleSweep();
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startObserver);
    else startObserver();
    window.addEventListener('load', function () { scheduleSweep(); setTimeout(scheduleSweep, 2000); });
  } catch (e) {}
})();
