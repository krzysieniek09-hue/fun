#!/usr/bin/env node
/* ========================================================================
   Playwave music server — run this on a ZimaBoard 2 (or any box with Node,
   v18+) to stream a music folder to the Playwave web app. Zero dependencies.

   Usage:
     node zima-server.js [musicDir] [port]

     musicDir  folder to scan for audio (default: ./music, or $MUSIC_DIR)
     port      HTTP port               (default: 8090,   or $PORT)

   Endpoints:
     GET /                 the Playwave web app (served from ../)
     GET /api/songs        JSON manifest of every audio file found
     GET /stream/<id>      the audio itself, with HTTP Range support
   ======================================================================== */

"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

const MUSIC_DIR = path.resolve(process.argv[2] || process.env.MUSIC_DIR || "./music");
const PORT = Number(process.argv[3] || process.env.PORT || 8090);
const APP_DIR = path.resolve(__dirname, "..");

const AUDIO_TYPES = {
  ".mp3": "audio/mpeg",
  ".m4a": "audio/mp4",
  ".aac": "audio/aac",
  ".flac": "audio/flac",
  ".ogg": "audio/ogg",
  ".opus": "audio/ogg",
  ".wav": "audio/wav",
  ".webm": "audio/webm",
};

const STATIC_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

// ---------- Library scan ----------

/** id -> absolute file path */
let fileIndex = new Map();

function scanLibrary() {
  const songs = [];
  fileIndex = new Map();

  function walk(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (e.name.startsWith(".")) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        walk(full);
        continue;
      }
      const ext = path.extname(e.name).toLowerCase();
      if (!AUDIO_TYPES[ext]) continue;

      const rel = path.relative(MUSIC_DIR, full);
      // Stable id from the relative path; safe for URLs.
      const id = Buffer.from(rel).toString("base64url");
      fileIndex.set(id, full);

      // "Artist - Title.mp3" → artist/title; otherwise filename is the title.
      const base = e.name.slice(0, -ext.length);
      const dash = base.split(" - ");
      const parent = path.dirname(rel);
      songs.push({
        id,
        title: dash.length > 1 ? dash.slice(1).join(" - ").trim() : base,
        artist: dash.length > 1 ? dash[0].trim() : "Unknown artist",
        album: parent === "." ? "" : path.basename(parent),
        url: `/stream/${id}`,
        size: (() => { try { return fs.statSync(full).size; } catch { return 0; } })(),
      });
    }
  }

  walk(MUSIC_DIR);
  songs.sort((a, b) =>
    (a.artist + a.title).localeCompare(b.artist + b.title, undefined, { sensitivity: "base" })
  );
  return songs;
}

// ---------- HTTP helpers ----------

function cors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Range");
  res.setHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");
}

function sendJson(res, status, body) {
  const buf = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": buf.length,
  });
  res.end(buf);
}

function streamFile(req, res, filePath, contentType) {
  let stat;
  try {
    stat = fs.statSync(filePath);
  } catch {
    return sendJson(res, 404, { error: "not found" });
  }

  const range = req.headers.range;
  const headers = {
    "Content-Type": contentType,
    "Accept-Ranges": "bytes",
    "Cache-Control": "no-cache",
  };

  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!m || (m[1] === "" && m[2] === "")) {
      res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
      return res.end();
    }
    let start = m[1] === "" ? stat.size - Number(m[2]) : Number(m[1]);
    let end = m[1] !== "" && m[2] !== "" ? Number(m[2]) : stat.size - 1;
    start = Math.max(0, start);
    end = Math.min(end, stat.size - 1);
    if (start > end) {
      res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
      return res.end();
    }
    headers["Content-Range"] = `bytes ${start}-${end}/${stat.size}`;
    headers["Content-Length"] = end - start + 1;
    res.writeHead(206, headers);
    if (req.method === "HEAD") return res.end();
    fs.createReadStream(filePath, { start, end }).pipe(res);
  } else {
    headers["Content-Length"] = stat.size;
    res.writeHead(200, headers);
    if (req.method === "HEAD") return res.end();
    fs.createReadStream(filePath).pipe(res);
  }
}

// ---------- Server ----------

const server = http.createServer((req, res) => {
  cors(res);

  if (req.method === "OPTIONS") {
    res.writeHead(204);
    return res.end();
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    return sendJson(res, 405, { error: "method not allowed" });
  }

  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const pathname = decodeURIComponent(url.pathname);

  if (pathname === "/api/songs") {
    return sendJson(res, 200, scanLibrary());
  }

  if (pathname.startsWith("/stream/")) {
    const id = pathname.slice("/stream/".length);
    if (!fileIndex.has(id)) scanLibrary(); // refresh index (new files / restart)
    const filePath = fileIndex.get(id);
    if (!filePath || !filePath.startsWith(MUSIC_DIR)) {
      return sendJson(res, 404, { error: "unknown song id" });
    }
    const type = AUDIO_TYPES[path.extname(filePath).toLowerCase()] || "application/octet-stream";
    return streamFile(req, res, filePath, type);
  }

  // Everything else: serve the web app so the ZimaBoard hosts player + music.
  // Audio types are allowed too, for the bundled demo tracks in demo/.
  let rel = pathname === "/" ? "index.html" : pathname.slice(1);
  const filePath = path.resolve(APP_DIR, rel);
  const ext = path.extname(filePath).toLowerCase();
  const type = STATIC_TYPES[ext] || AUDIO_TYPES[ext];
  if (!filePath.startsWith(APP_DIR + path.sep) || !type) {
    return sendJson(res, 404, { error: "not found" });
  }
  if (!fs.existsSync(filePath)) return sendJson(res, 404, { error: "not found" });
  streamFile(req, res, filePath, type);
});

server.listen(PORT, "0.0.0.0", () => {
  const count = scanLibrary().length;
  console.log("Playwave music server");
  console.log(`  music dir : ${MUSIC_DIR} (${count} songs found)`);
  console.log(`  app + api : http://0.0.0.0:${PORT}`);
  console.log("  open the address above from any device on your network");
});
