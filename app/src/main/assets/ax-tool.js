/* Aqar Exit Tool — injected into https://aqarexit.com
 * Loads every unit for a region group (beats the site's 192-per-search cap by
 * splitting the search), computes total contract price and price per m², and
 * adds filters/sorts the site does not have.
 */
(function () {
  'use strict';
  if (window.__AX_LOADED__) return;
  window.__AX_LOADED__ = true;

  // ---------------------------------------------------------------- config
  var CAP = 192;          // max units the site returns per search
  var PAGE = 48;          // site's page step
  var CONCURRENCY = 3;
  var DELAY_MS = 150;
  var QNAME_BUDGET = 250; // max name/developer searches per oversized cell

  var TYPES = ['شقة', 'دوبلكس', 'تاون هاوس', 'توين هاوس', 'فيلا', 'شاليه', 'بنتهاوس', 'محل', 'مكتب'];
  var SORT_VARIANTS = [
    'sort=newest', 'sort=cash_asc', 'sort=installment_asc', 'sort=spread_desc', 'sort=delivery_asc',
    'beds=2&sort=newest', 'beds=2&sort=cash_asc', 'beds=3&sort=newest', 'beds=3&sort=cash_asc',
    'beds=4&sort=newest', 'maxCash=1000000&sort=newest', 'maxCash=2000000&sort=newest',
    'maxCash=3000000&sort=newest', 'maxCash=3000000&sort=spread_desc', 'maxCash=5000000&sort=newest',
    'maxCash=5000000&sort=spread_desc', 'flexible=1&sort=newest', 'flexible=1&sort=cash_asc',
    'deliveryBy=2026&sort=newest', 'deliveryBy=2027&sort=newest', 'deliveryBy=2028&sort=newest',
    'contractBy=2022&sort=newest', 'contractBy=2023&sort=newest', 'contractBy=2024&sort=newest',
    'maxInstallment=50000&sort=newest', 'maxInstallment=100000&sort=newest'
  ];
  // unit types seen on cards but not in the site's type dropdown; used only if the server honours them
  var EXTRA_TYPES = ['عيادة', 'صيدلية', 'أرض', 'استوديو', 'إداري', 'وحدة إدارية'];

  var G_NEWCAIRO = ['التجمع الخامس', 'التجمع الأول', 'التجمع الثالث', 'القاهرة الجديدة', 'الرحاب'];
  var G_EAST = ['العاصمة الإدارية الجديدة', 'الشروق', 'العبور', 'المستقبل سيتي', 'بدر', 'هليوبوليس الجديدة', 'مدينتي'];
  var G_EAST_FREE = ['نور', 'حدائق العاصمة']; // areas typed by sellers, not in the site's area filter
  var G_OCT = ['6 أكتوبر', 'الشيخ زايد', 'زايد الجديدة', 'حدائق أكتوبر', 'أكتوبر الجديدة', 'الثورة الخضراء',
    'دريم لاند', 'بيفرلي هيلز', 'المهندسين الجديدة', 'الحي المتميز'];
  var G_OTHER = ['مدينة نصر', 'مصر الجديدة', 'المعادي', 'زهراء المعادي', 'المقطم', 'الزمالك', 'وسط البلد',
    'جاردن سيتي', 'شبرا', 'عين شمس', 'المطرية', 'الزيتون', 'حدائق القبة', 'السلام', 'النزهة', 'شيراتون',
    'حلوان', 'المعصرة', 'التبين', 'المهندسين', 'الدقي', 'العجوزة', 'الهرم', 'فيصل', 'الجيزة', 'إمبابة',
    'الوراق', 'أوسيم', 'البدرشين', 'الحوامدية', 'صفط اللبن', 'كرداسة'];
  var GROUPS = [
    { id: 'all', label: 'القاهرة الكبرى كلها', areas: [].concat(G_NEWCAIRO, G_EAST, G_OCT, G_OTHER), free: G_EAST_FREE },
    { id: 'newcairo', label: 'القاهرة الجديدة (التجمعات والرحاب)', areas: G_NEWCAIRO, free: [] },
    { id: 'east', label: 'العاصمة والشروق والعبور والمستقبل', areas: G_EAST, free: G_EAST_FREE },
    { id: 'oct', label: 'ناحية أكتوبر وزايد', areas: G_OCT, free: [] },
    { id: 'other', label: 'باقي القاهرة والجيزة', areas: G_OTHER, free: [] }
  ];
  // every area in the site's own area dropdown (as of Oct 2026), selectable one by one
  var SITE_AREAS = ['التجمع الخامس', 'التجمع الأول', 'التجمع الثالث', 'القاهرة الجديدة', 'الرحاب', 'مدينتي',
    'الشروق', 'العبور', 'بدر', 'المستقبل سيتي', 'العاصمة الإدارية الجديدة', 'هليوبوليس الجديدة', '6 أكتوبر',
    'الشيخ زايد', 'زايد الجديدة', 'حدائق أكتوبر', 'أكتوبر الجديدة', 'الثورة الخضراء', 'دريم لاند', 'بيفرلي هيلز',
    'المهندسين الجديدة', 'الحي المتميز', 'مدينة نصر', 'مصر الجديدة', 'المعادي', 'المقطم', 'زهراء المعادي',
    'الزمالك', 'وسط البلد', 'جاردن سيتي', 'شبرا', 'عين شمس', 'المطرية', 'الزيتون', 'حدائق القبة', 'السلام',
    'النزهة', 'شيراتون', 'حلوان', 'المعصرة', 'التبين', 'المهندسين', 'الدقي', 'العجوزة', 'الهرم', 'فيصل',
    'الجيزة', 'إمبابة', 'الوراق', 'أوسيم', 'البدرشين', 'الحوامدية', 'صفط اللبن', 'كرداسة', 'الساحل الشمالي',
    'العلمين الجديدة', 'مارينا', 'سيدي عبد الرحمن', 'رأس الحكمة', 'الضبعة', 'مرسى مطروح', 'سيدي كرير',
    'الهانوفيل', 'الغردقة', 'الجونة', 'سهل حشيش', 'مكادي', 'سفاجا', 'مرسى علم', 'شرم الشيخ', 'دهب', 'نويبع',
    'طابا', 'العين السخنة', 'رأس سدر', 'الإسكندرية', 'سموحة', 'سان ستيفانو', 'لوران', 'ميامي', 'سيدي بشر',
    'المنتزه', 'العجمي', 'برج العرب', 'برج العرب الجديدة', 'الإبراهيمية', 'محرم بك', 'العصافرة', 'المندرة',
    'أبو قير', 'بورسعيد', 'الإسماعيلية', 'السويس', 'دمياط', 'رأس البر', 'دمياط الجديدة', 'المنصورة',
    'المنصورة الجديدة', 'طنطا', 'الزقازيق', 'بنها', 'شبين الكوم', 'دمنهور', 'كفر الشيخ', 'المحلة الكبرى',
    'الفيوم', 'بني سويف', 'المنيا', 'أسيوط', 'سوهاج', 'قنا', 'الأقصر', 'أسوان', 'الغردقة الجديدة',
    'طيبة الجديدة', 'أخميم الجديدة'];
  function findGroup(id) {
    if (id && id.indexOf('a:') === 0) {
      var a = id.slice(2);
      return { id: id, label: a, areas: [a], free: [] };
    }
    return GROUPS.filter(function (x) { return x.id === id; })[0] || GROUPS[1];
  }

  // ---------------------------------------------------------------- state
  var S = {
    units: new Map(),      // code -> unit
    seen: new Set(),       // every unit code read so far in this run (live progress)
    seenUnits: new Map(),  // same, with data — kept if the run is stopped midway
    expected: null,        // site's total for the selection (sum of area counts)
    coverage: [],          // [{area, count, got}]
    running: false,
    cancel: false,
    serverMaxPriceOK: true,
    view: 'table',
    compound: null         // {name, area} when in compound mode
  };

  function loadSettings() {
    try { return JSON.parse(localStorage.getItem('ax_settings') || '{}'); } catch (e) { return {}; }
  }
  function saveSettings(o) {
    try { localStorage.setItem('ax_settings', JSON.stringify(o)); } catch (e) { /* ignore */ }
  }

  // ---------------------------------------------------------------- utils
  function num(s) {
    if (s == null) return null;
    var m = String(s).replace(/[^\d.]/g, '');
    return m ? Number(m) : null;
  }
  function fmt(n) { return n == null ? '—' : Math.round(n).toLocaleString('en-US'); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function norm(s) {
    return String(s || '').toLowerCase()
      .replace(/[ً-ٰٟـ]/g, '')
      .replace(/[أإآٱ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي')
      .replace(/ڤ/g, 'ف').replace(/چ/g, 'ج').replace(/گ/g, 'ك').replace(/پ/g, 'ب')
      .replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/\s+/g, ' ');
  }
  function enc(v) { return encodeURIComponent(v); }

  // ---------------------------------------------------------------- parsing
  function texts(doc, el) {
    var w = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT), a = [], n;
    while ((n = w.nextNode())) { var t = n.textContent.trim(); if (t) a.push(t); }
    return a;
  }
  function after(t, label) { var i = t.indexOf(label); return i >= 0 ? t[i + 1] : null; }

  function parseUnits(doc) {
    var out = [];
    var arts = doc.querySelectorAll('article[data-analytics-unit]');
    for (var k = 0; k < arts.length; k++) {
      var art = arts[k];
      var t = texts(doc, art);
      if (!t.length) continue;
      var id = art.getAttribute('data-analytics-unit');
      var codeI = -1;
      for (var i = 0; i < t.length; i++) if (/^U-\d+$/.test(t[i])) { codeI = i; break; }
      var h2 = art.querySelector('h2');
      var name = h2 ? h2.textContent.trim() : '';
      // spec line: "شقة · 2 غرف · 2 حمام · 104 م²" (or any line with م² / غرف)
      var specI = -1;
      for (var q = 0; q < t.length; q++) if (/م²|غرف|حمام/.test(t[q])) { specI = q; break; }
      // location line: "Developer · Area" (has " · ", comes before the spec, not a price/label)
      var loc = '';
      if (codeI > 0) loc = t[codeI - 1];
      else for (var r = 0; r < (specI < 0 ? t.length : specI); r++) if (t[r].indexOf(' · ') >= 0 && !/ج\.م|غرف|م²/.test(t[r])) loc = t[r];
      var dev = '', area = loc;
      var p = loc.lastIndexOf(' · ');
      if (p >= 0) { dev = loc.slice(0, p).trim(); area = loc.slice(p + 3).trim(); }
      var spec = specI >= 0 ? t[specI] : '';
      var parts = spec.split(' · ');
      function part(word) { for (var j = 0; j < parts.length; j++) if (parts[j].indexOf(word) >= 0) return num(parts[j]); return null; }
      var cash = num(after(t, 'المطلوب كاش دلوقتي'));
      var remaining = num(after(t, 'المتبقي للمطور'));
      var m2 = part('م²');
      var total = (cash != null && remaining != null) ? cash + remaining : null;
      var a = art.querySelector('a[href*="/buy/opportunity/"]');
      if (codeI < 0 && cash == null && !name) continue; // not a unit card
      out.push({
        id: id,
        code: codeI >= 0 ? t[codeI] : 'id:' + id,
        name: name,
        nameKey: norm(name),
        dev: dev,
        area: area,
        type: (spec && parts[0] && !/غرف|حمام|م²/.test(parts[0])) ? parts[0] : '',
        beds: part('غرف'),
        m2: m2,
        cash: cash,
        remaining: remaining,
        total: total,
        ppm: (total && m2) ? total / m2 : null,
        neg: t.some(function (x) { return x.indexOf('قابل للتفاوض') >= 0; }),
        delivery: t.indexOf('الاستلام') >= 0 ? num(after(t, 'الاستلام')) : null,
        url: 'https://aqarexit.com' + (a ? a.getAttribute('href').split('?')[0] : '/buy/opportunity/' + id)
      });
    }
    return out;
  }

  // ---------------------------------------------------------------- fetching
  var queue = [], active = 0;
  function limited(fn) {
    return new Promise(function (resolve, reject) {
      queue.push(function () {
        active++;
        fn().then(resolve, reject).finally(function () {
          active--;
          setTimeout(next, DELAY_MS);
        });
      });
      next();
    });
  }
  function next() { while (active < CONCURRENCY && queue.length) queue.shift()(); }

  var fetchCount = 0;
  function getPage(qs, show) {
    return limited(function () {
      if (S.cancel) return Promise.reject(new Error('cancelled'));
      fetchCount++;
      setStatus();
      var url = '/opportunities?show=' + show + (qs ? '&' + qs : '');
      function once() {
        var ctl = new AbortController();
        var timer = setTimeout(function () { ctl.abort(); }, 30000);
        return fetch(url, { credentials: 'include', signal: ctl.signal })
          .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
          })
          .finally(function () { clearTimeout(timer); });
      }
      return once()
        .catch(function () { return sleep(1500).then(once); })   // one retry
        .then(function (h) {
          var doc = new DOMParser().parseFromString(h, 'text/html');
          var m = doc.body.textContent.match(/(\d[\d,]*)\s*وحدة متاحة/);
          var units = parseUnits(doc);
          units.forEach(function (u) { S.seen.add(u.code); S.seenUnits.set(u.code, u); });
          return { count: m ? Number(m[1].replace(/,/g, '')) : null, units: units };
        });
    });
  }
  function showFor(count) { return Math.min(CAP, Math.max(PAGE, Math.ceil(count / PAGE) * PAGE)); }

  // fetch everything matching qs; returns Map(code->unit) and the server count
  async function fetchAll(qs, splitLevel) {
    var first = await getPage(qs, PAGE);
    var got = new Map();
    first.units.forEach(function (u) { got.set(u.code, u); });
    var count = first.count || 0;
    if (count <= PAGE) return { count: count, got: got };
    if (count <= CAP) {
      var r = await getPage(qs, showFor(count));
      r.units.forEach(function (u) { got.set(u.code, u); });
      return { count: count, got: got };
    }
    // > CAP: split
    if (splitLevel === 0) {          // by type
      var parts = await Promise.all(TYPES.map(function (t) { return fetchAll(qs + '&types=' + enc(t), 1); }));
      parts.forEach(function (p) { p.got.forEach(function (u, c) { got.set(c, u); }); });
      if (got.size < count) {
        // other types: only trust the filter if it actually narrowed the result
        var extra = await Promise.all(EXTRA_TYPES.map(function (t) { return getPage(qs + '&types=' + enc(t), PAGE).then(function (r) { return { t: t, r: r }; }); }));
        for (var e = 0; e < extra.length; e++) {
          var er = extra[e].r;
          if (er.count != null && er.count > 0 && er.count < count) {
            var full = await fetchAll(qs + '&types=' + enc(extra[e].t), 1);
            full.got.forEach(function (u, c) { got.set(c, u); });
          }
        }
      }
      if (got.size < count) await sortUnion(qs, got, count);
      if (got.size < count) await nameSearch(qs, got, count);
      return { count: count, got: got };
    }
    if (splitLevel === 1) {          // by readiness
      var rs = await Promise.all(['ready', 'offplan'].map(function (rd) { return fetchAll(qs + '&readiness=' + rd, 2); }));
      rs.forEach(function (p) { p.got.forEach(function (u, c) { got.set(c, u); }); });
      if (got.size < count) await sortUnion(qs, got, count);
      if (got.size < count) await nameSearch(qs, got, count);
      return { count: count, got: got };
    }
    // level 2: cannot split further with exact filters
    await sortUnion(qs, got, count);
    if (got.size < count) await nameSearch(qs, got, count);
    return { count: count, got: got };
  }

  // stop trying once STALL_LIMIT requests in a row brought nothing new
  var STALL_LIMIT = 30;
  function addNew(got, units) {
    var before = got.size;
    units.forEach(function (u) { got.set(u.code, u); });
    return got.size - before;
  }

  async function sortUnion(qs, got, count) {
    var stale = 0;
    for (var i = 0; i < SORT_VARIANTS.length && got.size < count && stale < STALL_LIMIT; i += CONCURRENCY) {
      var batch = SORT_VARIANTS.slice(i, i + CONCURRENCY).map(function (v) { return getPage(qs + '&' + v, CAP); });
      var res = await Promise.all(batch);
      res.forEach(function (r) { if (addNew(got, r.units) > 0) stale = 0; else stale++; });
    }
  }

  async function nameSearch(qs, got, count) {
    var tried = new Set(), budget = QNAME_BUDGET, stale = 0;
    while (got.size < count && budget > 0 && stale < STALL_LIMIT) {
      var names = [];
      got.forEach(function (u) {
        if (u.name && !tried.has(u.nameKey)) { tried.add(u.nameKey); names.push(u.name); }
        var dk = 'dev:' + norm(u.dev);
        if (u.dev && !tried.has(dk)) { tried.add(dk); names.push(u.dev); }
      });
      if (!names.length) break;
      names = names.slice(0, budget);
      budget -= names.length;
      for (var b = 0; b < names.length && got.size < count && stale < STALL_LIMIT; b += CONCURRENCY) {
        await Promise.all(names.slice(b, b + CONCURRENCY).map(async function (n) {
          var r = await getPage(qs + '&q=' + enc(n), PAGE);
          var added = addNew(got, r.units);
          if (r.count > PAGE && r.count <= CAP) {
            var r2 = await getPage(qs + '&q=' + enc(n), showFor(r.count));
            added += addNew(got, r2.units);
          }
          if (added > 0) stale = 0; else stale++;
        }));
      }
    }
  }

  function serverQS(f) {
    var a = [];
    if (f.contractBy) a.push('contractBy=' + f.contractBy);
    if (f.totalMax && S.serverMaxPriceOK) a.push('maxPrice=' + f.totalMax);
    return a.join('&');
  }

  async function runGroup(group, f) {
    S.units = new Map(); S.coverage = []; S.seen = new Set(); S.seenUnits = new Map(); S.expected = null; fetchCount = 0;
    S.groupAreas = new Set(group.areas.map(norm));
    var base = serverQS(f);
    // quick count per area first, so progress shows the real target
    var counts = await Promise.all(group.areas.map(function (area) {
      return getPage('areas=' + enc(area) + (base ? '&' + base : ''), PAGE)
        .then(function (r) { return r.count || 0; }, function (e) { if (S.cancel) throw e; return 0; });
    }));
    S.expected = counts.reduce(function (a, b) { return a + b; }, 0);
    setStatus();
    await Promise.all(group.areas.map(async function (area) {
      var qs = 'areas=' + enc(area) + (base ? '&' + base : '');
      try {
        var r = await fetchAll(qs, 0);
        r.got.forEach(function (u, c) { S.units.set(c, u); });
        S.coverage.push({ area: area, count: r.count, got: r.got.size });
      } catch (e) {
        if (S.cancel) throw e;
        S.coverage.push({ area: area + ' (فشل التحميل)', count: null, got: 0, failed: true });
      }
      render();
    }));
    // free-text areas (not in the site's area filter): search by name, keep matching area
    for (var i = 0; i < group.free.length; i++) {
      var fa = group.free[i];
      var qs2 = 'q=' + enc(fa) + (base ? '&' + base : '');
      // only a few pages: the word also matches project names all over Egypt
      var pages = await Promise.all(['sort=newest', 'sort=cash_asc', 'sort=spread_desc'].map(function (v) { return getPage(qs2 + '&' + v, CAP); }));
      var kept = 0;
      pages.forEach(function (pg) {
        pg.units.forEach(function (u) {
          if (norm(u.area).indexOf(norm(fa)) >= 0 && !S.units.has(u.code)) { S.units.set(u.code, u); kept++; }
        });
      });
      S.coverage.push({ area: fa + ' (بحث بالاسم)', count: null, got: kept });
      render();
    }
    verifyMaxPrice(f);
  }

  // free-text compound search: keeps units whose (normalized) name contains the text
  async function runNameSearch(text, f) {
    S.units = new Map(); S.coverage = []; S.seen = new Set(); S.expected = null; fetchCount = 0;
    var base = serverQS(f);
    // several spellings of the same compound, separated by comma: "Aliva، اليفا، اليڤا"
    var terms = text.split(/[,،|+]/).map(function (x) { return x.trim(); }).filter(Boolean);
    var seenKeys = new Set();
    terms = terms.filter(function (t) { var k = norm(t); if (!k || seenKeys.has(k)) return false; seenKeys.add(k); return true; });
    var keys = terms.map(norm);
    var per = [];
    for (var i = 0; i < terms.length; i++) {
      var r = await fetchAll('q=' + enc(terms[i]) + (base ? '&' + base : ''), 0);
      var kept = 0;
      r.got.forEach(function (u, c) {
        if (keys.some(function (k) { return u.nameKey.indexOf(k) >= 0; })) { if (!S.units.has(c)) kept++; S.units.set(c, u); }
      });
      per.push(terms[i] + ': ' + kept);
      render();
    }
    S.coverage.push({ area: 'بحث: ' + terms.join('، '), count: null, got: S.units.size, kept: S.units.size, per: per });
    verifyMaxPrice(f);
  }

  async function runCompound(name, area, f) {
    S.units = new Map(); S.coverage = []; S.seen = new Set(); S.expected = null; fetchCount = 0;
    var base = serverQS(f);
    var qs = 'q=' + enc(name) + (area ? '&areas=' + enc(area) : '') + (base ? '&' + base : '');
    var r = await fetchAll(qs, 0);
    var key = norm(name), kept = 0;
    r.got.forEach(function (u, c) { if (u.nameKey === key) { S.units.set(c, u); kept++; } });
    S.coverage.push({ area: 'بحث الكمبوند: ' + name + (area ? ' — ' + area : ''), count: r.count, got: r.got.size, kept: kept });
    verifyMaxPrice(f);
  }

  // The site's maxPrice is assumed to filter on total contract price. If any returned
  // unit breaks that, stop sending it to the server and flag a rerun.
  function verifyMaxPrice(f) {
    if (!f.totalMax || !S.serverMaxPriceOK) return;
    var bad = false;
    S.units.forEach(function (u) { if (u.total != null && u.total > f.totalMax * 1.001) bad = true; });
    if (bad) {
      S.serverMaxPriceOK = false;
      S.needRerun = true;
    }
  }

  // ---------------------------------------------------------------- filtering / compare
  function readFilters() {
    var g = function (id) { var el = document.getElementById(id); return el ? el.value : ''; };
    var n = function (id) { var v = num(g(id)); return v || null; };
    return {
      group: g('ax-group') || 'newcairo',
      totalMin: n('ax-tmin'), totalMax: n('ax-tmax'),
      ppmMin: n('ax-pmin'), ppmMax: n('ax-pmax'),
      m2Min: n('ax-amin'), m2Max: n('ax-amax'),
      contractBy: g('ax-contract') || '',
      sort: g('ax-sort') || 'ppm'
    };
  }

  function filtered(f) {
    var arr = [];
    S.units.forEach(function (u) {
      if (f.totalMin && !(u.total >= f.totalMin)) return;
      if (f.totalMax && !(u.total <= f.totalMax)) return;
      if (f.ppmMin && !(u.ppm >= f.ppmMin)) return;
      if (f.ppmMax && !(u.ppm <= f.ppmMax)) return;
      if (f.m2Min && !(u.m2 >= f.m2Min)) return;
      if (f.m2Max && !(u.m2 <= f.m2Max)) return;
      arr.push(u);
    });
    var key = f.sort === 'payEndYear' ? 'ppm' : f.sort;
    arr.sort(function (a, b) {
      var x = a[key], y = b[key];
      if (x == null && y == null) return 0;
      if (x == null) return 1;
      if (y == null) return -1;
      return x - y;
    });
    return arr;
  }

  // cheapest 3 price-per-m² of OTHER units in the same compound (same normalized name + area)
  function compoundIndex() {
    var idx = new Map();
    S.units.forEach(function (u) {
      if (!u.ppm || !u.nameKey) return;
      var k = u.nameKey + '|' + norm(u.area);
      if (!idx.has(k)) idx.set(k, []);
      idx.get(k).push(u);
    });
    idx.forEach(function (list) { list.sort(function (a, b) { return a.ppm - b.ppm; }); });
    return idx;
  }
  function cheapestOthers(idx, u) {
    var list = idx.get(u.nameKey + '|' + norm(u.area)) || [];
    var out = [];
    for (var i = 0; i < list.length && out.length < 3; i++) if (list[i].code !== u.code) out.push(list[i]);
    return out;
  }

  // ---------------------------------------------------------------- UI
  var CSS = [
    '#ax-fab{position:fixed;bottom:18px;left:18px;z-index:2147483646;background:#0f766e;color:#fff;border:0;border-radius:28px;padding:12px 18px;font:600 15px system-ui,sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.3)}',
    '#ax-panel{position:fixed;inset:0;z-index:2147483647;background:#f8fafc;color:#0f172a;direction:rtl;font:14px system-ui,"Segoe UI",Tahoma,sans-serif;display:none;flex-direction:column}',
    '#ax-panel.open{display:flex}',
    '#ax-head{padding:10px 12px;background:#0f766e;color:#fff;display:flex;align-items:center;gap:8px}',
    '#ax-head b{flex:1;font-size:16px}',
    '#ax-head button{background:rgba(255,255,255,.18);color:#fff;border:0;border-radius:8px;padding:6px 10px;font:inherit}',
    '#ax-form{padding:10px 12px;display:grid;grid-template-columns:1fr 1fr;gap:8px;background:#fff;border-bottom:1px solid #e2e8f0}',
    '#ax-form label{display:flex;flex-direction:column;font-size:12px;color:#475569;gap:3px}',
    '#ax-form .full{grid-column:1/-1}',
    '#ax-form input,#ax-form select{font:inherit;padding:8px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;color:#0f172a;width:100%;box-sizing:border-box}',
    '#ax-form .row{display:flex;gap:6px}',
    '#ax-actions{grid-column:1/-1;display:flex;gap:8px;flex-wrap:wrap}',
    '#ax-actions button{flex:1;min-width:90px;padding:10px;border:0;border-radius:8px;font:600 14px inherit;background:#e2e8f0;color:#0f172a}',
    '#ax-actions button.pri{background:#0f766e;color:#fff}',
    '#ax-status{padding:8px 12px;font-size:12px;color:#334155;background:#f1f5f9;border-bottom:1px solid #e2e8f0}',
    '#ax-status .warn{color:#b45309;font-weight:600}',
    '#ax-results{flex:1;overflow:auto;-webkit-overflow-scrolling:touch}',
    '#ax-results table{border-collapse:collapse;width:max-content;min-width:100%;font-size:13px}',
    '#ax-results th,#ax-results td{padding:7px 8px;border-bottom:1px solid #e2e8f0;text-align:right;white-space:nowrap;vertical-align:top}',
    '#ax-results th{position:sticky;top:0;background:#e2e8f0;z-index:1}',
    '#ax-results td.n{font-variant-numeric:tabular-nums;direction:ltr;text-align:left}',
    '#ax-results a{color:#0f766e}',
    '.ax-btn{border:1px solid #0f766e;color:#0f766e;background:#fff;border-radius:6px;padding:3px 7px;font:12px inherit;margin-inline-start:4px}',
    '.ax-cmp{font-size:12px;color:#475569;white-space:normal;max-width:220px}',
    '.ax-cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:10px;padding:10px}',
    '.ax-card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:12px}',
    '.ax-card h4{margin:0 0 2px;font-size:15px}',
    '.ax-card .sub{color:#64748b;font-size:12px;margin-bottom:8px}',
    '.ax-card .big{font-size:18px;font-weight:700;direction:ltr;text-align:right}',
    '.ax-card .kv{display:flex;justify-content:space-between;font-size:13px;padding:2px 0}',
    '.ax-card .kv span:last-child{font-variant-numeric:tabular-nums;direction:ltr}',
    '.ax-tag{display:inline-block;background:#fef3c7;color:#92400e;border-radius:10px;padding:1px 7px;font-size:11px}'
  ].join('\n');

  function el(html) { var d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstChild; }

  function buildUI() {
    var st = document.createElement('style'); st.textContent = CSS; document.head.appendChild(st);
    var fab = el('<button id="ax-fab">🔍 الأداة</button>');
    fab.onclick = function () { panel.classList.add('open'); };
    document.body.appendChild(fab);

    var years = '<option value="">أي سنة</option>';
    for (var y = new Date().getFullYear(); y >= 2015; y--) years += '<option value="' + y + '">' + y + ' أو أقدم</option>';
    var groups = '<optgroup label="مجموعات">' +
      GROUPS.map(function (g) { return '<option value="' + g.id + '">' + esc(g.label) + '</option>'; }).join('') +
      '</optgroup><optgroup label="منطقة واحدة">' +
      SITE_AREAS.map(function (a) { return '<option value="a:' + esc(a) + '">' + esc(a) + '</option>'; }).join('') +
      '</optgroup>';

    var panel = el(
      '<div id="ax-panel">' +
      '<div id="ax-head"><b id="ax-title">أداة عقار إكزت</b><button id="ax-close">✕ قفل</button></div>' +
      '<div id="ax-form">' +
      '<label class="full" id="ax-group-wrap">المنطقة<select id="ax-group">' + groups + '</select></label>' +
      '<label class="full" id="ax-name-wrap">بحث باسم الكمبوند — كذا كتابة افصل بينهم بفاصلة (بيتجاهل المنطقة)<div class="row"><input id="ax-name" placeholder="مثال: Aliva، اليفا، اليڤا"><button type="button" id="ax-name-go" style="white-space:nowrap;padding:8px 12px;border:0;border-radius:8px;background:#0f766e;color:#fff;font:600 14px inherit">بحث</button></div></label>' +
      '<label>إجمالي العقد<div class="row"><input id="ax-tmin" inputmode="numeric" placeholder="من"><input id="ax-tmax" inputmode="numeric" placeholder="إلى"></div></label>' +
      '<label>سعر المتر<div class="row"><input id="ax-pmin" inputmode="numeric" placeholder="من"><input id="ax-pmax" inputmode="numeric" placeholder="إلى"></div></label>' +
      '<label>المساحة م²<div class="row"><input id="ax-amin" inputmode="numeric" placeholder="من"><input id="ax-amax" inputmode="numeric" placeholder="إلى"></div></label>' +
      '<label>سنة التعاقد<select id="ax-contract">' + years + '</select></label>' +
      '<label>ترتيب (من الأقل)<select id="ax-sort"><option value="ppm">سعر المتر</option><option value="total">إجمالي العقد</option><option value="m2">المساحة</option><option value="delivery">سنة الاستلام</option></select></label>' +
      '<label>العرض<select id="ax-view"><option value="table">جدول</option><option value="cards">كروت</option></select></label>' +
      '<div id="ax-actions"><button class="pri" id="ax-run">تحميل الوحدات</button><button id="ax-stop">إيقاف</button><button id="ax-export">تصدير Excel</button></div>' +
      '</div>' +
      '<div id="ax-status">اختار المنطقة واضغط "تحميل الوحدات". الفلاتر والترتيب بيتطبقوا فوراً بعد التحميل.</div>' +
      '<div id="ax-results"></div>' +
      '</div>');
    document.body.appendChild(panel);

    var s = loadSettings();
    ['ax-group', 'ax-tmin', 'ax-tmax', 'ax-pmin', 'ax-pmax', 'ax-amin', 'ax-amax', 'ax-contract', 'ax-sort', 'ax-view'].forEach(function (id) {
      var e = document.getElementById(id);
      if (s[id] != null) e.value = s[id];
      var live = ['ax-tmin', 'ax-pmin', 'ax-pmax', 'ax-amin', 'ax-amax', 'ax-sort', 'ax-view'].indexOf(id) >= 0;
      e.addEventListener(e.tagName === 'SELECT' ? 'change' : 'input', function () {
        persist();
        if (id === 'ax-view') S.view = e.value;
        if (live) render();
        if (id === 'ax-tmax') render();   // client side always; server side applies on next load
      });
    });
    S.view = document.getElementById('ax-view').value;

    document.getElementById('ax-close').onclick = function () {
      if (S.compound && window.AXApp && window.AXApp.closeTab) { window.AXApp.closeTab(); return; }
      panel.classList.remove('open');
    };
    document.getElementById('ax-run').onclick = function () { S.nameQuery = null; run(); };
    var goName = function () {
      var v = document.getElementById('ax-name').value.trim();
      if (!v) return;
      S.nameQuery = v;
      run();
    };
    document.getElementById('ax-name-go').onclick = goName;
    document.getElementById('ax-name').addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); goName(); } });
    document.getElementById('ax-stop').onclick = function () { S.cancel = true; };
    document.getElementById('ax-export').onclick = exportCSV;
    document.getElementById('ax-results').addEventListener('click', onResultsClick);
    return panel;
  }

  function persist() {
    var o = {};
    ['ax-group', 'ax-tmin', 'ax-tmax', 'ax-pmin', 'ax-pmax', 'ax-amin', 'ax-amax', 'ax-contract', 'ax-sort', 'ax-view'].forEach(function (id) {
      o[id] = document.getElementById(id).value;
    });
    saveSettings(o);
  }

  var statusExtra = '';
  function setStatus(msg) {
    if (msg != null) statusExtra = msg;
    var box = document.getElementById('ax-status');
    if (!box) return;
    var total = 0, got = 0, partial = [];
    var failed = [];
    S.coverage.forEach(function (c) {
      if (c.failed) failed.push(c.area);
      if (c.count != null) { total += c.count; got += Math.min(c.got, c.count); if (c.got < c.count) partial.push(c.area + ' ' + c.got + '/' + c.count); }
    });
    var html = '';
    if (S.running) html += 'جاري التحميل… طلبات: ' + fetchCount + ' · وحدات اتقرت: ' + S.seen.size +
      (S.expected ? ' من حوالي ' + S.expected.toLocaleString('en-US') : '') + ' · مناطق خلصت: ' + S.coverage.length + '<br>';
    if (S.nameQuery && !S.compound && S.coverage.length) {
      var cq = S.coverage[0];
      html += 'نتيجة البحث: <b>' + (cq.kept || 0) + '</b> وحدة' + (cq.per && cq.per.length > 1 ? ' (' + esc(cq.per.join(' · ')) + ')' : '') + '<br>';
    } else if (S.coverage.length && !S.compound) {
      html += 'التغطية: <b>' + got.toLocaleString('en-US') + ' من ' + total.toLocaleString('en-US') + '</b> وحدة' +
        (partial.length ? ' — <span class="warn">ناقص: ' + esc(partial.join('، ')) + '</span>' : (S.running ? '' : ' ✓ كاملة')) + '<br>';
    }
    if (S.compound && S.coverage.length) {
      var c = S.coverage[0];
      html += 'وحدات الكمبوند بنفس كتابة الاسم: <b>' + (c.kept || 0) + '</b> (من ' + c.got + ' نتيجة بحث)<br>';
    }
    if (failed.length) html += '<span class="warn">' + esc(failed.join('، ')) + ' — دوس "تحميل الوحدات" تاني</span><br>';
    html += statusExtra;
    box.innerHTML = html;
  }

  function render() {
    setStatus();
    var f = readFilters();
    var list = filtered(f);
    var idx = compoundIndex();
    var box = document.getElementById('ax-results');
    if (!S.units.size) { box.innerHTML = ''; return; }
    var head = '<div style="padding:6px 12px;font-size:12px;color:#475569">بعد الفلاتر: <b>' + list.length.toLocaleString('en-US') + '</b> وحدة</div>';
    var MAX = 600; // keep the DOM light on phones; export has everything
    var shown = list.slice(0, MAX);
    var more = list.length > MAX ? '<div style="padding:10px 12px;color:#64748b">معروض أول ' + MAX + ' — التصدير فيه الكل.</div>' : '';
    if (S.view === 'cards') {
      box.innerHTML = head + '<div class="ax-cards">' + shown.map(function (u) { return card(u, idx); }).join('') + '</div>' + more;
    } else {
      box.innerHTML = head + '<table><thead><tr>' +
        '<th>#</th><th>الكمبوند</th><th>المنطقة</th><th>النوع</th><th>م²</th><th>سعر المتر</th><th>إجمالي العقد</th>' +
        '<th>كاش</th><th>متبقي</th><th>استلام</th><th>التشطيب</th><th>آخر قسط</th><th>أرخص 3 متر في الكمبوند</th><th></th></tr></thead><tbody>' +
        shown.map(function (u, i) { return row(u, i, idx); }).join('') + '</tbody></table>' + more;
    }
    observeRows();
  }

  function cmpHTML(idx, u) {
    var o = cheapestOthers(idx, u);
    if (!o.length) return '<span class="ax-cmp">مفيش وحدات تانية بنفس الاسم</span>';
    return '<span class="ax-cmp">' + o.map(function (x) {
      return '<a href="' + esc(x.url) + '" data-open="' + esc(x.url) + '">' + fmt(x.ppm) + '</a>';
    }).join(' / ') + (o.length < 3 ? ' <span class="ax-tag">' + o.length + ' بس</span>' : '') + '</span>';
  }
  function btns(u) {
    return '<button class="ax-btn" data-open="' + esc(u.url) + '">الوحدة</button>' +
      '<button class="ax-btn" data-cmp="' + esc(u.name) + '" data-area="' + esc(u.area) + '">الكمبوند ↗</button>';
  }
  function row(u, i, idx) {
    return '<tr data-row="' + esc(u.code) + '"><td class="n">' + (i + 1) + '</td><td>' + esc(u.name) + '<br><small style="color:#64748b">' + esc(u.code) + (u.neg ? ' · <span class="ax-tag">تفاوض</span>' : '') + '</small></td>' +
      '<td>' + esc(u.area) + '</td><td>' + esc(u.type) + (u.beds ? ' · ' + u.beds + 'غ' : '') + '</td>' +
      '<td class="n">' + fmt(u.m2) + '</td><td class="n"><b>' + fmt(u.ppm) + '</b></td><td class="n">' + fmt(u.total) + '</td>' +
      '<td class="n">' + fmt(u.cash) + '</td><td class="n">' + fmt(u.remaining) + '</td><td class="n">' + (u.delivery || '—') + '</td>' +
      '<td data-det="' + esc(u.code) + '" data-k="fin">' + detText(u.fin) + '</td>' +
      '<td data-det="' + esc(u.code) + '" data-k="pay">' + detText(u.payEnd) + '</td>' +
      '<td>' + cmpHTML(idx, u) + '</td><td>' + btns(u) + '</td></tr>';
  }
  function card(u, idx) {
    return '<div class="ax-card" data-row="' + esc(u.code) + '"><h4>' + esc(u.name) + '</h4><div class="sub">' + esc(u.dev) + ' · ' + esc(u.area) + ' · ' + esc(u.code) + (u.neg ? ' · <span class="ax-tag">تفاوض</span>' : '') + '</div>' +
      '<div class="kv"><span>سعر المتر</span><span class="big">' + fmt(u.ppm) + '</span></div>' +
      '<div class="kv"><span>إجمالي العقد</span><span>' + fmt(u.total) + '</span></div>' +
      '<div class="kv"><span>' + esc(u.type) + (u.beds ? ' · ' + u.beds + ' غرف' : '') + '</span><span>' + fmt(u.m2) + ' م²</span></div>' +
      '<div class="kv"><span>كاش / متبقي</span><span>' + fmt(u.cash) + ' / ' + fmt(u.remaining) + '</span></div>' +
      '<div class="kv"><span>الاستلام</span><span>' + (u.delivery || '—') + '</span></div>' +
      '<div class="kv"><span>التشطيب</span><span data-det="' + esc(u.code) + '" data-k="fin">' + detText(u.fin) + '</span></div>' +
      '<div class="kv"><span>آخر قسط</span><span data-det="' + esc(u.code) + '" data-k="pay">' + detText(u.payEnd) + '</span></div>' +
      '<div style="margin:6px 0;font-size:12px">أرخص 3 متر في الكمبوند: ' + cmpHTML(idx, u) + '</div>' +
      '<div>' + btns(u) + '</div></div>';
  }

  function onResultsClick(e) {
    var t = e.target.closest('[data-open],[data-cmp]');
    if (!t) return;
    e.preventDefault();
    if (t.hasAttribute('data-open')) {
      openTab(t.getAttribute('data-open'));
    } else {
      var url = 'https://aqarexit.com/opportunities#ax=compound&name=' + enc(t.getAttribute('data-cmp')) +
        '&area=' + enc(t.getAttribute('data-area'));
      openTab(url);
    }
  }
  function openTab(url) {
    if (window.AXApp && window.AXApp.openTab) window.AXApp.openTab(url);
    else window.open(url, '_blank');
  }

  // ---------------------------------------------------------------- run
  async function run() {
    if (S.running) return;
    S.running = true; S.cancel = false; S.needRerun = false;
    var f = readFilters();
    persist();
    setStatus('');
    try {
      if (S.compound) await runCompound(S.compound.name, S.compound.area, f);
      else if (S.nameQuery) await runNameSearch(S.nameQuery, f);
      else await runGroup(findGroup(f.group), f);
      if (S.needRerun) {
        S.running = false;
        setStatus('<span class="warn">فلتر "حتى" لإجمالي العقد في الموقع طلع مش على إجمالي العقد — بعيد التحميل من غيره…</span>');
        return run();
      }
      setStatus('آخر تحميل: ' + new Date().toLocaleTimeString('ar-EG'));
    } catch (e) {
      setStatus('<span class="warn">' + (S.cancel || e.message === 'cancelled' ? 'اتوقف. المعروض هو اللي اتحمّل لحد الإيقاف.' : 'حصلت مشكلة: ' + esc(e.message)) + '</span>');
    } finally {
      S.running = false; queue.length = 0;
      if (S.groupAreas && !S.compound && !S.nameQuery) {
        S.seenUnits.forEach(function (u, c) { if (!S.units.has(c) && S.groupAreas.has(norm(u.area))) S.units.set(c, u); });
      }
      render();
    }
  }

  // ---------------------------------------------------------------- unit-page details (shown as written)
  // Cards don't carry finishing or the installment end; each unit's page does
  // ("التشطيب: نص تشطيب", "باقي على خلاص التقسيط: 5 سنة"). They load by themselves for the rows
  // you scroll to, and are kept on the phone for a week.
  var DET_TTL = 7 * 24 * 3600 * 1000;
  var DET_LABELS = { fin: 'التشطيب', pay: 'باقي على خلاص التقسيط', floor: 'الدور' };
  var detCache = (function () { try { return JSON.parse(localStorage.getItem('ax_details') || '{}'); } catch (e) { return {}; } })();
  var detSaveTimer = null;
  function saveDet() {
    clearTimeout(detSaveTimer);
    detSaveTimer = setTimeout(function () { try { localStorage.setItem('ax_details', JSON.stringify(detCache)); } catch (e) { /* full */ } }, 800);
  }
  function applyDet(u) {
    var c = detCache[u.id];
    if (c && Date.now() - c.t < DET_TTL) { u.fin = c.fin; u.payEnd = c.pay; u.floor = c.floor; return true; }
    return false;
  }
  function labelValue(t, label) {
    for (var i = 0; i < t.length; i++) {
      if (t[i].indexOf(label) === 0) {
        var rest = t[i].slice(label.length).replace(/^[\s:：]+/, '').trim();
        if (rest) return rest;
        var v = t[i + 1] || '';
        if (/^[:：]$/.test(v)) v = t[i + 2] || '';
        return v.trim();
      }
    }
    return '';
  }
  var detQueue = [], detActive = 0, detPending = new Set();
  function wantDetails(u) {
    if (u.fin !== undefined || applyDet(u) || detPending.has(u.id)) return;
    detPending.add(u.id); detQueue.push(u); pumpDetails();
  }
  function pumpDetails() {
    while (detActive < 2 && detQueue.length) {
      var u = detQueue.shift();
      detActive++;
      fetch(u.url, { credentials: 'include' }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      }).then((function (u) {
        return function (h) {
          var doc = new DOMParser().parseFromString(h, 'text/html');
          var t = texts(doc, doc.body);
          u.fin = labelValue(t, DET_LABELS.fin) || 'غير مذكور';
          u.payEnd = labelValue(t, DET_LABELS.pay) || 'غير مذكور';
          u.floor = labelValue(t, DET_LABELS.floor) || '';
          detCache[u.id] = { fin: u.fin, pay: u.payEnd, floor: u.floor, t: Date.now() };
          saveDet();
          paintDetails(u);
        };
      })(u)).catch(function () { /* leave as loading; will retry on next scroll */ })
        .finally((function (u) { return function () { detActive--; detPending.delete(u.id); setTimeout(pumpDetails, 120); }; })(u));
    }
  }
  function detText(v) { return v === undefined ? '<span style="color:#94a3b8">…</span>' : esc(v); }
  function paintDetails(u) {
    var els = document.querySelectorAll('[data-det="' + u.code + '"]');
    for (var i = 0; i < els.length; i++) {
      var k = els[i].getAttribute('data-k');
      els[i].innerHTML = detText(k === 'fin' ? u.fin : u.payEnd);
    }
  }
  var detObserver = ('IntersectionObserver' in window) ? new IntersectionObserver(function (entries) {
    entries.forEach(function (en) {
      if (!en.isIntersecting) return;
      var u = S.units.get(en.target.getAttribute('data-row'));
      if (u) wantDetails(u);
      detObserver.unobserve(en.target);
    });
  }, { rootMargin: '300px' }) : null;
  function observeRows() {
    var box = document.getElementById('ax-results');
    var rows = box.querySelectorAll('[data-row]');
    for (var i = 0; i < rows.length; i++) {
      var u = S.units.get(rows[i].getAttribute('data-row'));
      if (!u) continue;
      if (u.fin !== undefined || applyDet(u)) { paintDetails(u); continue; }
      if (detObserver) detObserver.observe(rows[i]); else if (i < 60) wantDetails(u);
    }
  }

  // ---------------------------------------------------------------- export (CSV, opens in Excel)
  function exportCSV() {
    var list = filtered(readFilters());
    if (!list.length) { setStatus('<span class="warn">مفيش وحدات للتصدير.</span>'); return; }
    var idx = compoundIndex();
    var cols = ['الكود', 'الكمبوند', 'المطور', 'المنطقة', 'النوع', 'الغرف', 'المساحة م2', 'سعر المتر', 'إجمالي العقد',
      'عمولة 1.25%', 'كاش', 'متبقي', 'الاستلام', 'التشطيب', 'آخر قسط', 'قابل للتفاوض', 'أرخص 1', 'أرخص 2', 'أرخص 3', 'الرابط'];
    var lines = [cols.join(',')];
    list.forEach(function (u) {
      var o = cheapestOthers(idx, u);
      var v = [u.code, u.name, u.dev, u.area, u.type, u.beds, u.m2, u.ppm && Math.round(u.ppm), u.total,
        u.total && Math.round(u.total * 0.0125), u.cash, u.remaining, u.delivery, u.fin, u.payEnd, u.neg ? 'نعم' : '',
        o[0] && Math.round(o[0].ppm), o[1] && Math.round(o[1].ppm), o[2] && Math.round(o[2].ppm), u.url];
      lines.push(v.map(function (x) {
        x = x == null ? '' : String(x);
        return /[",\n]/.test(x) ? '"' + x.replace(/"/g, '""') + '"' : x;
      }).join(','));
    });
    var content = '﻿' + lines.join('\r\n');
    var fname = 'aqarexit_' + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.csv';
    if (window.AXApp && window.AXApp.saveFile) {
      window.AXApp.saveFile(fname, content, 'text/csv');
    } else {
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
      a.download = fname; document.body.appendChild(a); a.click(); a.remove();
    }
  }

  // ---------------------------------------------------------------- boot
  function boot() {
    if (!document.body || location.hostname.indexOf('aqarexit.com') < 0) return;
    var panel = buildUI();
    if (window.AXApp) {
      // Android app: the search screen is the home screen
      document.getElementById('ax-title').textContent = 'Aqar Finder';
      panel.classList.add('open');
    }
    var h = new URLSearchParams(location.hash.replace(/^#/, ''));
    if (h.get('ax') === 'compound' && h.get('name')) {
      S.compound = { name: h.get('name'), area: h.get('area') || '' };
      document.getElementById('ax-title').textContent = 'كمبوند: ' + S.compound.name;
      document.getElementById('ax-group-wrap').style.display = 'none';
      document.getElementById('ax-name-wrap').style.display = 'none';
      document.getElementById('ax-sort').value = 'ppm';
      panel.classList.add('open');
      run();
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
