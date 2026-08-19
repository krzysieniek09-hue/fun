/* ========================================================================
   Playwave — player engine, library state, and cloud (home-server) client.

   Cloud source: any server that answers GET <base>/api/songs with
   [{ id, title, artist, album, duration?, url }] and streams audio at the
   given urls (see server/zima-server.js for a ready-made ZimaBoard server).
   ======================================================================== */

(() => {
  "use strict";

  const STORAGE_KEY = "playwave.serverUrl";
  const LIKES_KEY = "playwave.likes";

  const $ = (id) => document.getElementById(id);

  const audio = $("audio");

  // ---------- State ----------

  const state = {
    tracks: [],          // full library (cloud + local)
    queue: [],           // array of track ids in play order
    queuePos: -1,
    shuffle: false,
    repeat: "off",       // off | all | one
    serverUrl: null,
    connected: false,
    likes: new Set(JSON.parse(localStorage.getItem(LIKES_KEY) || "[]")),
    viewHistory: ["home"],
    viewPos: 0,
  };

  const currentTrack = () =>
    state.queuePos >= 0 ? byId(state.queue[state.queuePos]) : null;

  const byId = (id) => state.tracks.find((t) => t.id === id);

  // ---------- Helpers ----------

  function fmtTime(sec) {
    if (!Number.isFinite(sec) || sec < 0) return "-:--";
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${String(s).padStart(2, "0")}`;
  }

  function hashHue(str) {
    let h = 0;
    for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) >>> 0;
    return h % 360;
  }

  // Deterministic per-track gradient stands in for album art.
  function coverStyle(track) {
    const h = hashHue(track.artist + track.title);
    const h2 = (h + 60) % 360;
    return `background: linear-gradient(135deg, hsl(${h} 70% 45%), hsl(${h2} 75% 30%))`;
  }

  const NOTE_SVG =
    '<svg viewBox="0 0 24 24"><path d="M6 3h15v15.167a3.5 3.5 0 1 1-3.5-3.5H19V5H8v13.167a3.5 3.5 0 1 1-3.5-3.5H6V3z"/></svg>';
  const PLAY_SVG =
    '<svg viewBox="0 0 24 24"><path d="m7.05 3.606 13.49 7.788a.7.7 0 0 1 0 1.212L7.05 20.394A.7.7 0 0 1 6 19.788V4.212a.7.7 0 0 1 1.05-.606z"/></svg>';
  const CLOUD_SVG =
    '<svg viewBox="0 0 24 24"><path d="M6.5 20a5.5 5.5 0 0 1-.62-10.965 7.501 7.501 0 0 1 14.55 1.9A4.5 4.5 0 0 1 19.5 20h-13z"/></svg>';
  const DEVICE_SVG =
    '<svg viewBox="0 0 24 24"><path d="M7 2a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H7zm0 2h10v14H7V4zm5 15a1 1 0 1 0 0 2 1 1 0 0 0 0-2z"/></svg>';

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
    );
  }

  let toastTimer = null;
  function toast(msg, isError = false) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.toggle("error", isError);
    el.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add("hidden"), 3200);
  }

  // ---------- Views ----------

  function showView(name, pushHistory = true) {
    for (const v of ["home", "search", "library"]) {
      $(`view-${v}`).classList.toggle("hidden", v !== name);
    }
    document.querySelectorAll(".nav-item[data-view]").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === name);
    });
    if (pushHistory) {
      state.viewHistory = state.viewHistory.slice(0, state.viewPos + 1);
      state.viewHistory.push(name);
      state.viewPos = state.viewHistory.length - 1;
    }
    if (name === "search") $("searchInput").focus();
  }

  $("navBack").addEventListener("click", () => {
    if (state.viewPos > 0) showView(state.viewHistory[--state.viewPos], false);
  });
  $("navFwd").addEventListener("click", () => {
    if (state.viewPos < state.viewHistory.length - 1)
      showView(state.viewHistory[++state.viewPos], false);
  });

  document.querySelectorAll("[data-view]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.preventDefault();
      showView(el.dataset.view);
    });
  });

  // ---------- Rendering ----------

  function render() {
    renderQuickGrid();
    renderCardGrid();
    renderTable($("libraryTable"), state.tracks);
    renderSearch();
    renderSidebarList();
    $("homeEmpty").classList.toggle("hidden", state.tracks.length > 0);
    $("quickGrid").classList.toggle("hidden", state.tracks.length === 0);
    document.querySelector(".section-head").classList.toggle("hidden", state.tracks.length === 0);
    $("libMeta").textContent =
      `${state.tracks.length} song${state.tracks.length === 1 ? "" : "s"}`;
    updatePlayingHighlights();
  }

  function renderQuickGrid() {
    const grid = $("quickGrid");
    grid.innerHTML = "";
    for (const t of state.tracks.slice(0, 6)) {
      const el = document.createElement("div");
      el.className = "quick-card";
      el.innerHTML =
        `<div class="q-art" style="${coverStyle(t)}">${NOTE_SVG}</div>` +
        `<span class="q-title">${esc(t.title)}</span>` +
        `<button class="hover-play" title="Play">${PLAY_SVG}</button>`;
      el.addEventListener("click", () => playTrack(t.id));
      grid.appendChild(el);
    }
  }

  function renderCardGrid() {
    const grid = $("cardGrid");
    grid.innerHTML = "";
    for (const t of state.tracks.slice(0, 12)) {
      const el = document.createElement("div");
      el.className = "song-card";
      el.innerHTML =
        `<div class="card-art" style="${coverStyle(t)}">${NOTE_SVG}</div>` +
        `<div class="card-title">${esc(t.title)}</div>` +
        `<div class="card-sub">${esc(t.artist)}</div>` +
        `<button class="hover-play" title="Play">${PLAY_SVG}</button>`;
      el.addEventListener("click", () => playTrack(t.id));
      grid.appendChild(el);
    }
  }

  function renderTable(container, tracks) {
    container.innerHTML = "";
    if (!tracks.length) return;
    const head = document.createElement("div");
    head.className = "tt-head";
    head.innerHTML =
      '<span class="tt-num">#</span><span>Title</span><span>Album</span>' +
      "<span>Source</span><span class=\"tt-dur\">⏱</span>";
    container.appendChild(head);

    tracks.forEach((t, i) => {
      const row = document.createElement("div");
      row.className = "tt-row";
      row.dataset.trackId = t.id;
      const srcIcon =
        t.source === "cloud" ? CLOUD_SVG : t.source === "demo" ? NOTE_SVG : DEVICE_SVG;
      const srcLabel =
        t.source === "cloud" ? "Server" : t.source === "demo" ? "Demo" : "Local";
      row.innerHTML =
        `<span class="tt-num"><span class="num">${i + 1}</span>` +
        `<button class="row-play" title="Play">${PLAY_SVG}</button></span>` +
        `<span class="tt-title-cell">` +
        `<span class="mini-art" style="${coverStyle(t)}">${NOTE_SVG}</span>` +
        `<span class="tt-title-meta"><div class="tt-title">${esc(t.title)}</div>` +
        `<div class="tt-artist">${esc(t.artist)}</div></span></span>` +
        `<span class="tt-album">${esc(t.album || "—")}</span>` +
        `<span class="tt-source">${srcIcon}${srcLabel}</span>` +
        `<span class="tt-dur">${t.duration ? fmtTime(t.duration) : "–:––"}</span>`;
      row.addEventListener("click", () => playTrack(t.id, tracks));
      container.appendChild(row);
    });
  }

  function renderSidebarList() {
    const ul = $("sideTrackList");
    ul.innerHTML = "";
    for (const t of state.tracks) {
      const li = document.createElement("li");
      li.className = "side-track";
      li.dataset.trackId = t.id;
      li.innerHTML =
        `<span class="mini-art" style="${coverStyle(t)}">${NOTE_SVG}</span>` +
        `<span class="st-meta"><div class="st-title">${esc(t.title)}</div>` +
        `<div class="st-artist">${esc(t.artist)}</div></span>`;
      li.addEventListener("click", () => playTrack(t.id));
      ul.appendChild(li);
    }
  }

  function renderSearch() {
    const q = $("searchInput").value.trim().toLowerCase();
    const results = !q
      ? state.tracks
      : state.tracks.filter(
          (t) =>
            t.title.toLowerCase().includes(q) ||
            t.artist.toLowerCase().includes(q) ||
            (t.album || "").toLowerCase().includes(q)
        );
    renderTable($("searchResults"), results);
    $("searchNone").classList.toggle("hidden", !(q && results.length === 0));
    updatePlayingHighlights();
  }

  $("searchInput").addEventListener("input", renderSearch);

  function updatePlayingHighlights() {
    const cur = currentTrack();
    document.querySelectorAll("[data-track-id]").forEach((el) => {
      const isCur = cur && el.dataset.trackId === cur.id;
      el.classList.toggle("playing", Boolean(isCur));
      if (el.classList.contains("tt-row")) {
        const num = el.querySelector(".num");
        if (isCur) {
          const paused = audio.paused ? " paused" : "";
          num.innerHTML = `<span class="eq${paused}"><span></span><span></span><span></span></span>`;
        } else {
          num.textContent =
            [...el.parentElement.querySelectorAll(".tt-row")].indexOf(el) + 1;
        }
      }
    });
  }

  // ---------- Playback ----------

  function buildQueue(fromTracks) {
    const ids = (fromTracks || state.tracks).map((t) => t.id);
    if (state.shuffle) {
      for (let i = ids.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [ids[i], ids[j]] = [ids[j], ids[i]];
      }
    }
    return ids;
  }

  function playTrack(id, context) {
    state.queue = buildQueue(context);
    // In shuffle mode, keep the chosen track first.
    const idx = state.queue.indexOf(id);
    if (state.shuffle && idx > 0) {
      state.queue.splice(idx, 1);
      state.queue.unshift(id);
    }
    state.queuePos = state.queue.indexOf(id);
    loadAndPlay();
  }

  function loadAndPlay() {
    const t = currentTrack();
    if (!t) return;
    audio.src = t.url;
    audio.play().catch((err) => {
      if (err.name !== "AbortError") toast(`Couldn't play "${t.title}"`, true);
    });
    updateNowPlaying();
    updatePlayingHighlights();
    updateMediaSession(t);
  }

  function next(auto = false) {
    if (!state.queue.length) return;
    if (auto && state.repeat === "one") {
      audio.currentTime = 0;
      audio.play();
      return;
    }
    let pos = state.queuePos + 1;
    if (pos >= state.queue.length) {
      if (auto && state.repeat === "off") {
        state.queuePos = state.queue.length - 1;
        return; // end of queue — stop
      }
      pos = 0;
    }
    state.queuePos = pos;
    loadAndPlay();
  }

  function prev() {
    if (!state.queue.length) return;
    if (audio.currentTime > 3) {
      audio.currentTime = 0;
      return;
    }
    state.queuePos =
      state.queuePos <= 0 ? state.queue.length - 1 : state.queuePos - 1;
    loadAndPlay();
  }

  function togglePlay() {
    if (!currentTrack()) {
      if (state.tracks.length) playTrack(state.tracks[0].id);
      return;
    }
    if (audio.paused) audio.play();
    else audio.pause();
  }

  function updateNowPlaying() {
    const t = currentTrack();
    const art = $("npArt");
    if (!t) {
      $("npTitle").textContent = "Not playing";
      $("npArtist").textContent = "Pick a song to get started";
      art.classList.add("idle");
      art.removeAttribute("style");
      return;
    }
    $("npTitle").textContent = t.title;
    $("npArtist").textContent = t.artist;
    art.classList.remove("idle");
    art.setAttribute("style", coverStyle(t));
    $("likeBtn").classList.toggle("liked", state.likes.has(t.id));
  }

  function updateMediaSession(t) {
    if (!("mediaSession" in navigator)) return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: t.title,
      artist: t.artist,
      album: t.album || "",
    });
    navigator.mediaSession.setActionHandler("play", () => audio.play());
    navigator.mediaSession.setActionHandler("pause", () => audio.pause());
    navigator.mediaSession.setActionHandler("previoustrack", prev);
    navigator.mediaSession.setActionHandler("nexttrack", () => next());
  }

  // ---------- Player bar wiring ----------

  $("playBtn").addEventListener("click", togglePlay);
  $("nextBtn").addEventListener("click", () => next());
  $("prevBtn").addEventListener("click", prev);

  $("shuffleBtn").addEventListener("click", () => {
    state.shuffle = !state.shuffle;
    $("shuffleBtn").classList.toggle("active", state.shuffle);
    // Reshuffle the remaining queue around the current track.
    const cur = currentTrack();
    if (cur) {
      state.queue = buildQueue();
      const idx = state.queue.indexOf(cur.id);
      state.queue.splice(idx, 1);
      state.queue.unshift(cur.id);
      state.queuePos = 0;
    }
  });

  $("repeatBtn").addEventListener("click", () => {
    state.repeat =
      state.repeat === "off" ? "all" : state.repeat === "all" ? "one" : "off";
    $("repeatBtn").classList.toggle("active", state.repeat !== "off");
    $("repeatOneDot").classList.toggle("hidden", state.repeat !== "one");
  });

  $("likeBtn").addEventListener("click", () => {
    const t = currentTrack();
    if (!t) return;
    if (state.likes.has(t.id)) state.likes.delete(t.id);
    else state.likes.add(t.id);
    localStorage.setItem(LIKES_KEY, JSON.stringify([...state.likes]));
    $("likeBtn").classList.toggle("liked", state.likes.has(t.id));
  });

  const seekBar = $("seekBar");
  let seeking = false;

  function paintSlider(el, pct) {
    el.style.setProperty("--fill", `${pct}%`);
  }

  audio.addEventListener("timeupdate", () => {
    if (seeking || !audio.duration) return;
    const pct = (audio.currentTime / audio.duration) * 100;
    seekBar.value = pct * 10;
    paintSlider(seekBar, pct);
    $("curTime").textContent = fmtTime(audio.currentTime);
  });

  audio.addEventListener("loadedmetadata", () => {
    $("durTime").textContent = fmtTime(audio.duration);
    const t = currentTrack();
    if (t && !t.duration && Number.isFinite(audio.duration)) {
      t.duration = audio.duration;
      render();
    }
  });

  seekBar.addEventListener("input", () => {
    seeking = true;
    paintSlider(seekBar, seekBar.value / 10);
    if (audio.duration)
      $("curTime").textContent = fmtTime((seekBar.value / 1000) * audio.duration);
  });
  seekBar.addEventListener("change", () => {
    if (audio.duration) audio.currentTime = (seekBar.value / 1000) * audio.duration;
    seeking = false;
  });

  const volBar = $("volBar");
  let lastVol = 0.8;
  audio.volume = 0.8;
  paintSlider(volBar, 80);

  volBar.addEventListener("input", () => {
    audio.volume = volBar.value / 100;
    audio.muted = false;
    paintSlider(volBar, volBar.value);
    updateVolIcon();
  });

  $("muteBtn").addEventListener("click", () => {
    if (audio.volume > 0 && !audio.muted) {
      lastVol = audio.volume;
      audio.muted = true;
      volBar.value = 0;
    } else {
      audio.muted = false;
      audio.volume = lastVol || 0.8;
      volBar.value = audio.volume * 100;
    }
    paintSlider(volBar, volBar.value);
    updateVolIcon();
  });

  function updateVolIcon() {
    const muted = audio.muted || audio.volume === 0;
    $("muteBtn").querySelector(".icon-vol").classList.toggle("hidden", muted);
    $("muteBtn").querySelector(".icon-muted").classList.toggle("hidden", !muted);
  }

  function updatePlayIcon() {
    $("playBtn").querySelector(".icon-play").classList.toggle("hidden", !audio.paused);
    $("playBtn").querySelector(".icon-pause").classList.toggle("hidden", audio.paused);
    updatePlayingHighlights();
  }

  audio.addEventListener("play", updatePlayIcon);
  audio.addEventListener("pause", updatePlayIcon);
  audio.addEventListener("ended", () => next(true));
  audio.addEventListener("error", () => {
    const t = currentTrack();
    if (t) toast(`Playback failed for "${t.title}"`, true);
  });

  // Space toggles playback when not typing in a field.
  document.addEventListener("keydown", (e) => {
    if (e.code === "Space" && !/INPUT|TEXTAREA/.test(document.activeElement.tagName)) {
      e.preventDefault();
      togglePlay();
    }
  });

  // ---------- Library view buttons ----------

  $("libPlayAll").addEventListener("click", () => {
    if (state.tracks.length) playTrack(state.tracks[0].id);
  });
  $("libShuffle").addEventListener("click", () => {
    if (!state.tracks.length) return;
    state.shuffle = true;
    $("shuffleBtn").classList.add("active");
    const pick = state.tracks[Math.floor(Math.random() * state.tracks.length)];
    playTrack(pick.id);
  });

  // ---------- Bundled demo tracks ----------

  // Shipped alongside the app (demo/*.ogg) so there is something to play
  // before any server or local files are set up. Relative URLs work both
  // hosted and from file:// (the Android WebView loads assets this way).
  const DEMO_TRACKS = [
    { file: "neon-dusk.ogg", title: "Neon Dusk", duration: 21 },
    { file: "glass-waves.ogg", title: "Glass Waves", duration: 23 },
    { file: "midnight-transit.ogg", title: "Midnight Transit", duration: 19 },
    { file: "sunset-loop.ogg", title: "Sunset Loop", duration: 25 },
  ];

  function addDemoTracks(andPlay) {
    let firstId = null;
    for (const d of DEMO_TRACKS) {
      const id = `demo:${d.file}`;
      if (!firstId) firstId = id;
      if (byId(id)) continue;
      state.tracks.push({
        id,
        title: d.title,
        artist: "Playwave",
        album: "Playwave Demo",
        duration: d.duration,
        url: `demo/${d.file}`,
        source: "demo",
      });
    }
    render();
    if (andPlay && firstId) playTrack(firstId);
  }

  $("emptyDemoBtn").addEventListener("click", () => addDemoTracks(true));

  // ---------- Local files ----------

  function addLocalFiles(files) {
    let added = 0;
    for (const f of files) {
      if (!f.type.startsWith("audio/") && !/\.(mp3|m4a|flac|ogg|opus|wav|aac|webm)$/i.test(f.name)) continue;
      const base = f.name.replace(/\.[^.]+$/, "");
      const dash = base.split(" - ");
      const t = {
        id: `local:${f.name}:${f.size}`,
        title: dash.length > 1 ? dash.slice(1).join(" - ").trim() : base,
        artist: dash.length > 1 ? dash[0].trim() : "Unknown artist",
        album: "Local files",
        duration: null,
        url: URL.createObjectURL(f),
        source: "local",
      };
      if (!byId(t.id)) {
        state.tracks.push(t);
        added++;
      }
    }
    if (added) {
      render();
      toast(`Added ${added} song${added === 1 ? "" : "s"} from this device`);
    }
  }

  const filePicker = $("filePicker");
  filePicker.addEventListener("change", () => {
    addLocalFiles([...filePicker.files]);
    filePicker.value = "";
  });
  for (const id of ["addLocalBtn", "addLocalBtn2", "emptyLocalBtn"]) {
    $(id).addEventListener("click", () => filePicker.click());
  }

  // ---------- Cloud server ----------

  function normalizeBase(url) {
    let u = url.trim();
    if (!u) return null;
    if (!/^https?:\/\//i.test(u)) u = `http://${u}`;
    return u.replace(/\/+$/, "");
  }

  async function fetchManifest(base) {
    // Preferred: the bundled zima-server API. Fallback: a static songs.json
    // (for plain file hosts that can't run Node).
    for (const path of ["/api/songs", "/songs.json"]) {
      try {
        const res = await fetch(base + path, { signal: AbortSignal.timeout(8000) });
        if (!res.ok) continue;
        const data = await res.json();
        const list = Array.isArray(data) ? data : data.songs;
        if (Array.isArray(list)) return list;
      } catch {
        /* try next path */
      }
    }
    throw new Error(
      "No music API found at that address. Is zima-server.js running there?"
    );
  }

  async function connectServer(base, { silent = false } = {}) {
    const songs = await fetchManifest(base);
    // Replace any previous cloud tracks; keep local ones.
    state.tracks = state.tracks.filter((t) => t.source !== "cloud");
    for (const s of songs) {
      state.tracks.push({
        id: `cloud:${s.id ?? s.url}`,
        title: s.title || "Untitled",
        artist: s.artist || "Unknown artist",
        album: s.album || "",
        duration: s.duration || null,
        url: new URL(s.url, base + "/").href,
        source: "cloud",
      });
    }
    state.serverUrl = base;
    state.connected = true;
    localStorage.setItem(STORAGE_KEY, base);
    updateConnBadge();
    render();
    if (!silent)
      toast(`Connected — ${songs.length} song${songs.length === 1 ? "" : "s"} on your server`);
  }

  function disconnectServer() {
    state.tracks = state.tracks.filter((t) => t.source !== "cloud");
    state.connected = false;
    state.serverUrl = null;
    localStorage.removeItem(STORAGE_KEY);
    updateConnBadge();
    render();
    toast("Disconnected from server");
  }

  function updateConnBadge() {
    const badge = $("connBadge");
    badge.classList.toggle("online", state.connected);
    $("connBadgeText").textContent = state.connected
      ? new URL(state.serverUrl).host
      : "Offline";
    $("cloudCardSub").textContent = state.connected
      ? `Streaming from ${state.serverUrl}`
      : "Connect a ZimaBoard or any home server running the Playwave music server.";
    $("connectBtn").textContent = state.connected ? "Manage server" : "Connect server";
  }

  // ---------- Connect modal ----------

  const modal = $("connectModal");

  function openModal() {
    $("serverUrlInput").value = state.serverUrl || "";
    $("connectError").classList.add("hidden");
    $("disconnectBtn").classList.toggle("hidden", !state.connected);
    modal.classList.remove("hidden");
    $("serverUrlInput").focus();
  }
  const closeModal = () => modal.classList.add("hidden");

  for (const id of ["connectBtn", "emptyConnectBtn", "connBadge"]) {
    $(id).addEventListener("click", openModal);
  }
  $("cancelConnectBtn").addEventListener("click", closeModal);
  modal.addEventListener("click", (e) => {
    if (e.target === modal) closeModal();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeModal();
  });

  async function submitConnect() {
    const base = normalizeBase($("serverUrlInput").value);
    if (!base) return;
    const btn = $("doConnectBtn");
    btn.disabled = true;
    btn.textContent = "Connecting…";
    $("connectError").classList.add("hidden");
    try {
      await connectServer(base);
      closeModal();
    } catch (err) {
      const el = $("connectError");
      el.textContent = err.message || "Couldn't reach the server.";
      el.classList.remove("hidden");
    } finally {
      btn.disabled = false;
      btn.textContent = "Connect";
    }
  }

  $("doConnectBtn").addEventListener("click", submitConnect);
  $("serverUrlInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") submitConnect();
  });
  $("disconnectBtn").addEventListener("click", () => {
    disconnectServer();
    closeModal();
  });

  // ---------- Boot ----------

  function setGreeting() {
    const h = new Date().getHours();
    $("greeting").textContent =
      h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
  }

  async function boot() {
    setGreeting();
    render();
    updateConnBadge();

    // 1. If the app is being served by zima-server itself, use it directly.
    // 2. Otherwise reconnect to the last-used server.
    const candidates = [];
    if (location.protocol.startsWith("http")) candidates.push(location.origin);
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && !candidates.includes(saved)) candidates.push(saved);

    for (const base of candidates) {
      try {
        await connectServer(base, { silent: true });
        toast(`Connected to ${new URL(base).host}`);
        return;
      } catch {
        /* try next candidate */
      }
    }
  }

  boot();
})();
