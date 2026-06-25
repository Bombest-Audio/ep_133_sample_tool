/* EP-133 Sample Tool — shared behaviour. No deps. */
(function () {
  var root = document.documentElement;
  var THEME = "ep133.theme", SKU = "ep133.sku";

  function apply() {
    root.setAttribute("data-theme", localStorage.getItem(THEME) || "light");
    root.setAttribute("data-sku", localStorage.getItem(SKU) || "ep133");
    sync();
  }
  function sync() {
    var t = root.getAttribute("data-theme"), s = root.getAttribute("data-sku");
    var tb = document.querySelector("[data-toggle-theme] .lab");
    var sb = document.querySelector("[data-toggle-sku] .lab");
    if (tb) tb.textContent = t === "dark" ? "DARK" : "LIGHT";
    if (sb) sb.textContent = s === "ep1320" ? "EP-1320" : "EP-133";
  }
  apply();

  document.addEventListener("click", function (e) {
    var t = e.target.closest("[data-toggle-theme]");
    var s = e.target.closest("[data-toggle-sku]");
    if (t) {
      localStorage.setItem(THEME, root.getAttribute("data-theme") === "dark" ? "light" : "dark");
      apply();
    }
    if (s) {
      localStorage.setItem(SKU, root.getAttribute("data-sku") === "ep1320" ? "ep133" : "ep1320");
      apply();
    }
  });

  /* TOC scrollspy (protocol page) */
  var links = [].slice.call(document.querySelectorAll(".toc a"));
  if (links.length) {
    var map = {};
    links.forEach(function (a) {
      var id = a.getAttribute("href").slice(1);
      var el = document.getElementById(id);
      if (el) map[id] = a;
    });
    var io = new IntersectionObserver(function (ents) {
      ents.forEach(function (en) {
        if (en.isIntersecting) {
          links.forEach(function (l) { l.classList.remove("active"); });
          var a = map[en.target.id];
          if (a) a.classList.add("active");
        }
      });
    }, { rootMargin: "-80px 0px -70% 0px", threshold: 0 });
    Object.keys(map).forEach(function (id) { io.observe(document.getElementById(id)); });
  }
})();
