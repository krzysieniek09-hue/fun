/* ========================================================================
   Playwave on-device YouTube saving.

   Used when no music server is connected. Talks to YouTube's InnerTube API
   through YouTube.js (js/lib/ytjs.bundle.min.js, loaded lazily) and stores
   the audio as a blob in IndexedDB, so saved songs live on this device.

   This only works where cross-origin fetches to YouTube are possible —
   i.e. inside the Android app's WebView (universal file access) — not in a
   regular browser tab, where CORS blocks it; there the error message points
   at connecting a server instead. Extraction depends on YouTube not
   changing things; when it breaks, rebuild the bundle with
   scripts/bundle-ytjs.sh to pick up a fixed youtubei.js.
   ======================================================================== */

window.PhoneYT = (() => {
  "use strict";

  const DB_NAME = "playwave-device";
  const STORE = "songs";

  // ---------- IndexedDB ----------

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "id" });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function putSong(song) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(song);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async function allSaved() {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const req = db.transaction(STORE).objectStore(STORE).getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => reject(req.error);
    });
  }

  async function removeSong(id) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).delete(id);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  // ---------- YouTube.js (lazy) ----------

  let ytjsLoading = null;
  function loadYtjs() {
    if (window.YTJS) return Promise.resolve();
    if (!ytjsLoading) {
      ytjsLoading = new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "js/lib/ytjs.bundle.min.js";
        s.onload = resolve;
        s.onerror = () => reject(new Error("Couldn't load the YouTube engine."));
        document.head.appendChild(s);
      });
    }
    return ytjsLoading;
  }

  function videoIdFrom(raw) {
    let u;
    try {
      u = new URL(raw);
    } catch {
      return null;
    }
    if (/(^|\.)youtu\.be$/.test(u.hostname)) return u.pathname.slice(1).split("/")[0] || null;
    if (!/(^|\.)youtube\.com$/.test(u.hostname)) return null;
    if (u.pathname.startsWith("/shorts/") || u.pathname.startsWith("/live/") ||
        u.pathname.startsWith("/embed/")) {
      return u.pathname.split("/")[2] || null;
    }
    return u.searchParams.get("v");
  }

  function friendlyError(err) {
    // A CORS-blocked fetch surfaces as a bare TypeError: that means we're in
    // a normal browser tab, where on-device extraction can't work.
    if (err instanceof TypeError) {
      return "This browser can't reach YouTube directly. Use the Android app, " +
             "or connect to your music server and it will download instead.";
    }
    return err && err.message ? err.message : "YouTube download failed.";
  }

  // ---------- Download ----------

  async function download(rawUrl, onProgress) {
    const id = videoIdFrom(rawUrl);
    if (!id) throw new Error("That doesn't look like a YouTube video link.");
    await loadYtjs();

    const yt = await YTJS.Innertube.create({
      fetch: (input, init) => fetch(input, init),
    });

    // Different InnerTube clients work at different times; try a few.
    let info = null;
    let format = null;
    let lastErr = null;
    for (const client of [undefined, "IOS", "TV_EMBEDDED", "ANDROID"]) {
      try {
        info = await yt.getBasicInfo(id, client);
        format = info.chooseFormat({ type: "audio", quality: "best" });
        if (format) break;
      } catch (err) {
        lastErr = err;
      }
    }
    if (!format) throw lastErr || new Error("No playable audio found for that video.");

    const url = format.decipher(yt.session.player);
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`Audio stream request failed (${resp.status}).`);

    const total = Number(resp.headers.get("content-length")) || Number(format.content_length) || 0;
    const reader = resp.body.getReader();
    const chunks = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.length;
      if (total && onProgress) onProgress(Math.min(99, (received / total) * 100));
    }

    const mime = (format.mime_type || "audio/mp4").split(";")[0].trim();
    const song = {
      id: `yt:${id}`,
      title: (info.basic_info && info.basic_info.title) || "YouTube audio",
      artist: (info.basic_info && info.basic_info.author) || "YouTube",
      duration: (info.basic_info && info.basic_info.duration) || null,
      mime,
      blob: new Blob(chunks, { type: mime }),
      savedAt: Date.now(),
    };
    await putSong(song);
    if (onProgress) onProgress(100);
    return song;
  }

  return { download, allSaved, removeSong, friendlyError };
})();
