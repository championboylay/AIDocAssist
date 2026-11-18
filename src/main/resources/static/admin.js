// Admin: a link with data-dialog="<id>" opens that <dialog> instead of navigating. Without JS (or
// without <dialog> support) the link still goes to its page, e.g. the sync confirmation page.
document.addEventListener("click", function (e) {
  var link = e.target.closest("a[data-dialog]");
  if (link) {
    var dlg = document.getElementById(link.getAttribute("data-dialog"));
    if (dlg && dlg.showModal) { e.preventDefault(); dlg.showModal(); }
    return;
  }
  // A click on the dimmed backdrop lands on the <dialog> itself (its content is inside .panel): close.
  if (e.target.tagName === "DIALOG" && e.target.open) e.target.close();
});

// Auto-refresh while a sync runs (<meta name="refresh">), but never while a dialog is open:
// a reload would close the Sync confirmation mid-read. Without JS, a <noscript> meta refresh does it.
(function () {
  var meta = document.querySelector('meta[name="refresh"]');
  if (!meta) return;
  setInterval(function () {
    if (!document.querySelector("dialog[open]")) location.reload();
  }, 1000 * Number(meta.content));
})();

// File drop zones (_dropzone.html). Click and drop already work natively (the invisible input covers
// the zone); this adds the drag highlight and lists the chosen files, flagging any the server will
// refuse (type or size) before Sync is pressed. The server still checks everything itself.
(function () {
  function size(n) { return n >= 1048576 ? (n / 1048576).toFixed(1) + " MB" : Math.max(1, Math.round(n / 1024)) + " KB"; }
  document.querySelectorAll(".dropzone").forEach(function (zone) {
    var input = zone.querySelector(".dz-input"), list = zone.querySelector(".dz-list");
    var accept = (zone.getAttribute("data-accept") || "").toLowerCase().split(",");
    var max = Number(zone.getAttribute("data-max"));
    ["dragenter", "dragover"].forEach(function (t) { zone.addEventListener(t, function () { zone.classList.add("dz-over"); }); });
    ["dragleave", "drop"].forEach(function (t) { zone.addEventListener(t, function () { zone.classList.remove("dz-over"); }); });
    input.addEventListener("change", function () {
      list.textContent = "";
      Array.prototype.forEach.call(input.files, function (f) {
        var ext = (f.name.match(/\.[^.]+$/) || [""])[0].toLowerCase();
        var why = accept.indexOf(ext) < 0 ? "type not supported" : f.size > max ? "over 20 MB" : "";
        var li = document.createElement("li"), name = document.createElement("span"), meta = document.createElement("span");
        name.textContent = f.name;  // textContent: a file name is never parsed as HTML
        meta.className = "dz-size"; meta.textContent = why ? why + " — will be refused" : size(f.size);
        if (why) li.className = "dz-bad";
        li.appendChild(name); li.appendChild(meta); list.appendChild(li);
      });
    });
  });
})();
