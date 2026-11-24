/* X SATURN – إخفاء العناصر الإعلانية وتعطيل الطبقات الشفافة الخادعة.
 * الاستبدالات: __SELECTORS__ (مصفوفة)، __OVERLAYS__ (true/false).
 * آمن للتكرار. يعيد [عدد العناصر المعالَجة لأول مرة, عدد النوافذ المنبثقة المحجوبة]. */
(function () {
  var count = 0, pop = 0;
  try {
    var sels = __SELECTORS__;
    var valid = [];
    for (var i = 0; i < sels.length; i++) {
      try { document.createDocumentFragment().querySelector(sels[i]); valid.push(sels[i]); } catch (e) {}
    }
    // لا نُخفي العناصر الفارغة: مواقع كشف المانع تضع «طُعماً» فارغاً بصنف إعلاني وتتأكد أنه ظاهر
    var safe = [];
    for (var q = 0; q < valid.length; q++) {
      var cand = valid[q] + ':not(:empty)';
      try { document.createDocumentFragment().querySelector(cand); safe.push(cand); } catch (e) {}
    }
    valid = safe;
    if (valid.length) {
      var ID = 'xsaturn-cosmetic';
      var st = document.getElementById(ID);
      if (!st) {
        st = document.createElement('style');
        st.id = ID;
        (document.head || document.documentElement).appendChild(st);
      }
      st.textContent = valid.join(',\n') + '{display:none !important;}';
      var els = document.querySelectorAll(valid.join(','));
      for (var j = 0; j < els.length; j++) {
        if (!els[j].hasAttribute('data-xs-hidden')) {
          els[j].setAttribute('data-xs-hidden', '1');
          count++;
        }
      }
    }
    if (__OVERLAYS__ && document.body) {
      var vw = window.innerWidth, vh = window.innerHeight;
      var all = document.body.getElementsByTagName('*');
      var limit = Math.min(all.length, 4000);
      for (var k = 0; k < limit; k++) {
        var el = all[k];
        if (el.hasAttribute('data-xs-ov')) continue;
        var cs = window.getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
        if (cs.display === 'none' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') continue;
        var r = el.getBoundingClientRect();
        if (r.width < vw * 0.7 || r.height < vh * 0.4) continue;
        var opacity = parseFloat(cs.opacity);
        if (isNaN(opacity)) opacity = 1;
        var bg = cs.backgroundColor || '';
        var transparent = opacity < 0.15 ||
          bg === 'transparent' ||
          bg === 'rgba(0, 0, 0, 0)' ||
          /rgba?\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*0(?:\.0+)?\s*\)/.test(bg);
        var z = parseInt(cs.zIndex, 10);
        if (isNaN(z)) z = 0;
        // طبقة كبيرة شفافة بـ z-index عالٍ = غالباً clickjack. نشترط أن تكون فارغة (بلا نص أو
        // أزرار أو صور)، حتى لا نخفي نافذة حقيقية داخل غلاف شفاف. نعطّل اللمس فقط ولا نحذفها.
        var empty = !(el.textContent || '').trim() &&
          !el.querySelector('img,video,iframe,canvas,a,button,input,svg,form,select,textarea');
        if ((opacity <= 0.02 || (transparent && empty)) && z >= 10) {
          el.setAttribute('data-xs-ov', '1');
          el.style.setProperty('pointer-events', 'none', 'important');
          count++;
        }
      }
    }
  } catch (e) {}
  try { if (typeof window.__xsPopCount === 'number') pop = window.__xsPopCount; } catch (e) {}
  return [count, pop];
})();
