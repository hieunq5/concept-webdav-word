# client (attachment-editor-client)

Next.js (App Router, TypeScript) frontend. Deliberately minimal: one page,
one button. Its entire job is to hand off to desktop MS Word and then show
that the server received Word's save — it never touches the file bytes
itself.

## Layout

```
app/
  layout.tsx     Root layout, page metadata
  page.tsx       The home page: button + polled file metadata
  globals.css    Plain CSS, light/dark via prefers-color-scheme
lib/
  config.ts      Env-driven API base URL / filename / URL builders
```

## Running

```bash
npm install
cp .env.local.example .env.local   # only if overriding defaults
npm run dev
```

Defaults (`lib/config.ts`) assume the server from `../server` is running on
`http://localhost:8080`:

- `NEXT_PUBLIC_API_BASE_URL` — default `http://localhost:8080`
- `NEXT_PUBLIC_DOCUMENT_FILENAME` — default `sample.docx`

## How the button actually works

There is no client-side API call involved in opening Word. The button sets:

```
window.location.href = `ms-word:ofe|u|${webdavUrl}`
```

which is a custom URI scheme the OS hands off to the locally installed
desktop Word (Office 2013+ Windows / 2016+ Mac). Word then talks to the
server's `/webdav/...` endpoint directly (LOCK, GET, and on every Save, a
PUT) — the browser and this Next.js app are not in that loop at all. See
root `PROMPT.md` for why this needs WebDAV rather than a normal
download/upload.

The page polls `GET /api/documents/{file}/meta` every 4 seconds purely to
display that a save landed (updated `lastModified`/`size`) — that's the
*only* thing that talks to the plain REST API from the client.

## Gotchas specific to this module

- **`ms-word:ofe|...` only works if Word is installed on the machine
  running the browser**, and the OS has actually registered the protocol
  handler (true for a normal Office desktop install; not true in a
  browser-only sandbox/CI environment, or with Word for the web). There's
  no reliable client-side feature-detection for this — if the handler
  isn't registered, the browser will typically show its own "no
  application found" prompt, which this app can't intercept or work around.
- **CORS**: the metadata `fetch()` calls are real browser requests and
  depend on the server's `app.cors.allowed-origins` (in
  `server/src/main/resources/application.yml`) including this app's
  origin. Changing the client's dev port means updating that list too.
- **Internal npm registry**: `package.json` versions are pinned to
  whatever this environment's private npm mirror actually had cached at
  the time (checked via `npm view <pkg> dist-tags`), not necessarily the
  newest upstream release — a plain `npm install <pkg>@latest` can 404 if
  the mirror hasn't fetched that tarball through yet. If a version bump
  fails to install, check `npm view <pkg> dist-tags` for what the mirror
  actually resolves before assuming the package is broken.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
