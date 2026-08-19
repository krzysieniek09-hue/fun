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

  // ---------- Transport ----------

  // Everything YouTube-bound goes over XMLHttpRequest, not fetch():
  // the Android WebView's setAllowUniversalAccessFromFileURLs setting lifts
  // cross-origin restrictions for XHR only — fetch() from a file:// page is
  // still CORS-blocked, which is exactly how v1.3 failed on-device.

  const IS_WEBVIEW = /; wv\)/.test(navigator.userAgent) ||
                     location.protocol === "file:";

  function parseXhrHeaders(raw) {
    const h = new Headers();
    for (const line of (raw || "").trim().split(/[\r\n]+/)) {
      const i = line.indexOf(":");
      if (i > 0) {
        try {
          h.append(line.slice(0, i).trim(), line.slice(i + 1).trim());
        } catch {
          /* skip unparsable header */
        }
      }
    }
    return h;
  }

  // fetch()-compatible shim on top of XHR, for YouTube.js.
  async function xhrFetch(input, init) {
    init = init || {};
    const req = typeof Request !== "undefined" && input instanceof Request ? input : null;
    const url = req ? req.url : String(input);
    const method = (init.method || (req && req.method) || "GET").toUpperCase();
    let body = init.body;
    if (body === undefined && req && method !== "GET" && method !== "HEAD") {
      body = await req.clone().arrayBuffer();
    }
    const headers = new Headers(init.headers || (req ? req.headers : undefined));

    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open(method, url, true);
      x.responseType = "arraybuffer";
      headers.forEach((v, k) => {
        try {
          x.setRequestHeader(k, v);
        } catch {
          /* forbidden header — browser sets its own */
        }
      });
      x.onload = () => {
        const status = x.status || 200;
        const noBody =
          method === "HEAD" || status === 204 || status === 205 || status === 304;
        const resp = new Response(noBody ? null : x.response, {
          status,
          statusText: x.statusText,
          headers: parseXhrHeaders(x.getAllResponseHeaders()),
        });
        try {
          Object.defineProperty(resp, "url", { value: x.responseURL || url });
        } catch {
          /* keep default */
        }
        resolve(resp);
      };
      x.onerror = () => reject(new TypeError("Network request failed"));
      x.ontimeout = () => reject(new TypeError("Network request timed out"));
      x.send(body === undefined ? null : body);
    });
  }

  // Binary download with progress, also over XHR.
  function xhrDownload(url, onProgress) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open("GET", url, true);
      x.responseType = "arraybuffer";
      x.onprogress = (e) => {
        if (onProgress && e.total) onProgress(Math.min(99, (e.loaded / e.total) * 100));
      };
      x.onload = () => {
        if (x.status >= 200 && x.status < 300) resolve(x.response);
        else reject(new Error(`Audio stream request failed (${x.status}).`));
      };
      x.onerror = () => reject(new TypeError("Network request failed"));
      x.send();
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
    const netFail =
      err instanceof TypeError ||
      (err && err.cause instanceof TypeError) ||
      /Network request failed/.test((err && err.message) || "");
    // A blocked/failed request means different things by environment.
    if (netFail && !IS_WEBVIEW) {
      return "This browser blocks direct YouTube access (CORS). Use the Android " +
             "app, or connect to your music server and it will download instead.";
    }
    if (netFail) {
      return "Couldn't reach YouTube from the app. Check the phone's internet " +
             "connection and try again; if it keeps failing, connect to your music " +
             "server and it will download instead. (" + ((err && err.message) || "network") + ")";
    }
    return err && err.message ? err.message : "YouTube download failed.";
  }

  // ---------- Download ----------

  // Tags an error with the stage it happened in, so a failure the user
  // reports (screenshot) says exactly what broke.
  function stageError(stage, err) {
    const e = new Error(
      `[${stage}] ${err && err.message ? err.message : String(err)}`
    );
    e.cause = err;
    e.stage = stage;
    return e;
  }

  async function download(rawUrl, onProgress) {
    const id = videoIdFrom(rawUrl);
    if (!id) throw new Error("That doesn't look like a YouTube video link.");

    try {
      await loadYtjs();
    } catch (err) {
      throw stageError("engine", err);
    }

    let yt;
    try {
      yt = await YTJS.Innertube.create({ fetch: xhrFetch });
    } catch (err) {
      throw stageError("connect", err);
    }

    // Different InnerTube clients work at different times; try a few.
    let info = null;
    let format = null;
    let lastErr = null;
    for (const client of [undefined, "IOS", "TV_EMBEDDED", "ANDROID", "WEB"]) {
      try {
        info = await yt.getBasicInfo(id, client);
        format = info.chooseFormat({ type: "audio", quality: "best" });
        if (format) break;
      } catch (err) {
        lastErr = err;
      }
    }
    if (!format) throw stageError("extract", lastErr || new Error("no playable audio for that video"));

    let url, buffer;
    try {
      url = format.decipher(yt.session.player);
    } catch (err) {
      throw stageError("decipher", err);
    }
    try {
      buffer = await xhrDownload(url, onProgress);
    } catch (err) {
      throw stageError("stream", err);
    }

    const mime = (format.mime_type || "audio/mp4").split(";")[0].trim();
    const song = {
      id: `yt:${id}`,
      title: (info.basic_info && info.basic_info.title) || "YouTube audio",
      artist: (info.basic_info && info.basic_info.author) || "YouTube",
      duration: (info.basic_info && info.basic_info.duration) || null,
      mime,
      blob: new Blob([buffer], { type: mime }),
      savedAt: Date.now(),
    };
    await putSong(song);
    if (onProgress) onProgress(100);
    return song;
  }

  return { download, allSaved, removeSong, friendlyError };
})();
