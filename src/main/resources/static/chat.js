// Ported from SKB skb/api/static/chat.js; only the storage key and the assistant's name/mark differ.
// Chat page enhancements (plain JS, no framework). Everything also works without this file:
// forms still submit, citations still open via #anchors. This adds:
//  - on Ask: your question appears in the thread at once, followed by a pending answer
//    ("Searching and checking sources…") while the answer is prepared. No streaming: answers are
//    shown only after their citations pass the check, so the page reloads with the finished turn;
//  - the evidence panel staying on the chosen citation across follow-ups (page reloads);
//  - the sidebar's delete confirmation dialog (without JS the icon does nothing).
(function () {
  var KEY = "aidocassist-evidence";
  function store(v) { try { v ? sessionStorage.setItem(KEY, v) : sessionStorage.removeItem(KEY); } catch (e) {} }
  function stored() { try { return sessionStorage.getItem(KEY); } catch (e) { return null; } }

  // The panel shows one citation card plus its turn's "Suggested" list under it. Opening the list
  // ("+N more found") shows the turn's first card above it (none on an unanswered turn).
  function open(id) {
    var target = document.getElementById(id);
    if (!target) return false;
    document.querySelectorAll(".ev.open, .sg.open").forEach(function (el) { el.classList.remove("open"); });
    document.querySelectorAll(".chip-ev.active").forEach(function (el) { el.classList.remove("active"); });
    var turn = target.getAttribute("data-turn");
    var isList = target.classList.contains("sg");
    var card = isList ? document.querySelector('.ev[data-turn="' + turn + '"]') : target;
    var list = isList ? target : document.querySelector('.sg[data-turn="' + turn + '"]');
    if (card) card.classList.add("open");
    if (list) list.classList.add("open");
    if (card) document.querySelectorAll('.chip-ev[data-ev="' + card.id + '"]').forEach(function (el) { el.classList.add("active"); });
    if (location.hash) history.replaceState(null, "", location.pathname);  // drop any #target so only one shows
    if (isList) target.scrollIntoView({ block: "nearest" });
    store(id);
    return true;
  }
  function close() {
    document.querySelectorAll(".ev.open, .sg.open").forEach(function (el) { el.classList.remove("open"); });
    document.querySelectorAll(".chip-ev.active").forEach(function (el) { el.classList.remove("active"); });
    if (location.hash) history.replaceState(null, "", location.pathname);
    store(null);
  }

  document.addEventListener("click", function (e) {
    var link = e.target.closest("[data-ev]");
    if (link) { e.preventDefault(); open(link.getAttribute("data-ev")); return; }
    if (e.target.closest("[data-close]")) { e.preventDefault(); close(); return; }
    // A sidebar delete icon opens its confirmation <dialog> (as admin.js does); a click on the
    // dimmed backdrop lands on the <dialog> itself and closes it.
    var opener = e.target.closest("a[data-dialog]");
    var dlg = opener && document.getElementById(opener.getAttribute("data-dialog"));
    if (dlg && dlg.showModal) { e.preventDefault(); dlg.showModal(); return; }
    if (e.target.tagName === "DIALOG" && e.target.open) e.target.close();
  });

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text) n.textContent = text;  // textContent: the question is never parsed as HTML
    return n;
  }
  function now() { var d = new Date(); return ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2); }

  // Show the question + a pending answer in the thread (creating the thread view on the empty screen).
  function showPending(question) {
    var convo = document.getElementById("convo");
    if (!convo) {
      var main = document.querySelector(".main");
      // Hide, don't remove: the form being submitted lives in .empty, and a browser drops the
      // submission of a form taken out of the page (the question was shown but never sent).
      main.querySelectorAll(".empty").forEach(function (n) { n.style.display = "none"; });
      convo = el("div", "convo"); convo.id = "convo";
      main.appendChild(convo);
    }
    // Same shapes as a saved turn (chat.html): your bubble, then an Assistant card.
    var q = el("div", "q"); q.appendChild(el("div", "q-text", question)); q.appendChild(el("span", "time", now()));
    var a = el("div", "a pending"); a.setAttribute("role", "status"); a.setAttribute("aria-live", "polite");
    var head = el("div", "a-head"); head.appendChild(el("span", "mark", "A")); head.appendChild(el("span", "who-name", "Assistant"));
    head.appendChild(el("span", "time", now()));
    var p = el("p", "pending-text", "Searching and checking sources");
    p.appendChild(el("span", "dots"));
    var body = el("div", "a-body"); body.appendChild(p);
    body.appendChild(el("span", "hint", "This can take up to half a minute: every citation is checked against its source before the answer is shown."));
    a.appendChild(head); a.appendChild(body);
    convo.appendChild(q); convo.appendChild(a);
    convo.scrollTop = convo.scrollHeight;
  }

  var sent = false;
  document.querySelectorAll("form[data-ask]").forEach(function (form) {
    form.addEventListener("submit", function (e) {
      if (sent) { e.preventDefault(); return; }  // one question at a time
      var field = form.querySelector('textarea[name="question"]');
      var question = (e.submitter && e.submitter.name === "question") ? e.submitter.value : (field ? field.value : "");
      if (!question.trim()) return;
      sent = true;
      showPending(question.trim());
      document.querySelectorAll("form[data-ask]").forEach(function (f) {
        f.classList.add("busy");
        f.querySelectorAll('textarea[name="question"]').forEach(function (i) {
          i.readOnly = true;  // readOnly, not disabled: disabled fields aren't submitted
          // The question now shows in the thread; empty the box once the browser has read the form
          // (it reads it after this handler, so clearing it right now would send an empty question).
          setTimeout(function () { i.value = ""; i.placeholder = "Waiting for the answer…"; }, 0);
        });
      });
    });
  });

  // Question box: Enter sends, Shift+Enter adds a new line (the default for a textarea). Not while an
  // input method is composing (Burmese/Chinese/...): Enter there confirms a character, it must not send.
  // The box grows with its text up to its CSS max-height, then scrolls.
  document.querySelectorAll('form[data-ask] textarea[name="question"]').forEach(function (box) {
    function fit() { box.style.height = "auto"; box.style.height = box.scrollHeight + 2 + "px"; }
    box.addEventListener("input", fit);
    box.addEventListener("keydown", function (e) {
      if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
      e.preventDefault();
      if (box.value.trim()) box.form.requestSubmit();  // runs the submit handler above (pending turn, one at a time)
    });
    fit();
  });

  // A just-answered turn (#latest) keeps its arrival motion even after open() below drops the #hash.
  var hash = location.hash.slice(1);
  var latest = hash === "latest" && document.getElementById("latest");
  if (latest) latest.classList.add("arrived");
  // Restore: an #ev- anchor wins, else the last evidence opened in this tab (if it's on this page).
  if (hash.indexOf("ev-") === 0 || hash.indexOf("sg-") === 0) open(hash);
  else if (stored()) open(stored());
  var convo = document.getElementById("convo");
  if (convo) convo.scrollTop = convo.scrollHeight;
})();
