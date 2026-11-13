(function () {
  var theme = "dark";
  var mode = "system";
  try {
    var saved = localStorage.getItem("astra-theme");
    if (saved === "dark" || saved === "light") {
      theme = saved;
      mode = saved;
    } else if (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches) {
      theme = "light";
    }
  } catch (e) {
    theme = "dark";
  }
  document.documentElement.setAttribute("data-theme", theme);
  document.documentElement.setAttribute("data-theme-mode", mode);
})();
