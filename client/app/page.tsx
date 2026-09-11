"use client";

import { useCallback, useEffect, useState } from "react";
import { DOCUMENT_FILENAME, apiUrl, webdavUrl } from "@/lib/config";

type DocumentMeta = {
  filename: string;
  size: number;
  lastModified: string;
  etag: string;
};

export default function Home() {
  const [meta, setMeta] = useState<DocumentMeta | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch(apiUrl(`/api/documents/${DOCUMENT_FILENAME}/meta`), {
        cache: "no-store",
      });
      if (!res.ok) throw new Error(`Server returned ${res.status}`);
      setMeta(await res.json());
      setError(null);
    } catch (err) {
      setError(
        err instanceof Error
          ? `Can't reach the server: ${err.message}`
          : "Can't reach the server"
      );
    }
  }, []);

  useEffect(() => {
    refresh();
    // Word saves happen out-of-band (desktop app talking WebDAV directly to
    // the server), so this page polls to notice the update rather than
    // waiting on a websocket/event it has no way to receive.
    const interval = setInterval(refresh, 4000);
    return () => clearInterval(interval);
  }, [refresh]);

  function openInWord() {
    window.location.href = `ms-word:ofe|u|${webdavUrl(DOCUMENT_FILENAME)}`;
  }

  return (
    <main>
      <h1>Attachment Inline Editor</h1>
      <p className="subtitle">WebDAV + desktop Word concept</p>

      <div className="card">
        <button className="primary" onClick={openInWord} disabled={!meta}>
          Open in Word
        </button>
        <p className="hint">
          Opens {DOCUMENT_FILENAME} directly in desktop Microsoft Word. Edit
          it and hit Save (Ctrl/Cmd+S) in Word — it saves straight back to
          this server, no download/upload step.
        </p>

        {meta && (
          <>
            <div style={{ marginTop: 20 }}>
              <div className="meta-row">
                <span>File</span>
                <span>{meta.filename}</span>
              </div>
              <div className="meta-row">
                <span>Size</span>
                <span>{(meta.size / 1024).toFixed(1)} KB</span>
              </div>
              <div className="meta-row">
                <span>Last modified</span>
                <span>{new Date(meta.lastModified).toLocaleString()}</span>
              </div>
            </div>
            <p className="status">Auto-refreshing every 4s to reflect saves from Word.</p>
          </>
        )}

        {error && <p className="status error">{error}</p>}
      </div>
    </main>
  );
}
