# Concept: WebDAV + Desktop MS Word Inline Editor

## Original ask

Build a concept for WebDAV + MS Word (desktop) with two modules:

- **server** — a simple Spring Boot service providing an API to get/upload a
  `.docx` file (stored under `/resources`).
- **client** — a Next.js frontend: a simple home page with a button. Clicking
  it opens the file in desktop MS Word; editing and saving in Word
  auto-saves back to the Spring Boot service.

## Why WebDAV (the key design decision)

A plain HTTP download/upload can't do what was asked: "click a button, Word
opens, Save writes back to the server automatically." A browser can only
trigger a *download*; there's no web API that hands a file to a native
desktop app and gets edits back.

Desktop Office has one mechanism for this: the `ms-word:ofe|u|<url>`
protocol handler (Office 2013+ on Windows, Office 2016+ on Mac). It's the
same mechanism SharePoint/OneDrive use for their "Edit in Desktop App"
buttons. When invoked, Word:

1. Sends `OPTIONS` to the URL and checks for a `DAV` header (WebDAV
   compliance class) and `MS-Author-Via: DAV`.
2. If present, treats the URL as WebDAV-editable: `LOCK`s the resource,
   `GET`s the bytes, opens them locally, and on every Save issues a `PUT`
   back to the same URL (then `UNLOCK`s on close).
3. If absent, it falls back to a plain download — edits never leave the
   local temp copy.

So the server has to speak a minimal subset of WebDAV (class 2: locking) in
addition to whatever REST API it offers. That's the whole reason
`server/src/main/java/.../webdav/WebDavServlet.java` exists as a raw
`HttpServlet` rather than a `@RestController` — Spring MVC's `RequestMethod`
enum has no `PROPFIND`/`LOCK`/`UNLOCK`, only a real servlet can dispatch on
those verbs.

## Architecture

```
attachment-inline-editor/
  server/   Spring Boot 3 (Java 17)
  client/   Next.js (App Router, TypeScript)
```

**server** exposes two independent surfaces over the same file storage:

- `POST/GET /api/documents/...` — plain REST API (get/list/upload), used by
  the Next.js page for metadata display and as a non-Word fallback.
- `/webdav/{filename}` — WebDAV endpoint used *exclusively* by desktop
  Word's open/edit/autosave flow. Never called by the client's own
  `fetch()` code; the client only ever navigates the browser to
  `ms-word:ofe|u|<webdav-url>` and lets the OS hand off to Word.

Files live in an external directory outside the classpath/jar
(`./data/documents` by default), seeded from a bundled `sample.docx` on
first run — classpath resources inside a packaged jar aren't writable, and
both PUT (from Word) and upload (from the API) need to overwrite the file.

**client** is a single home page: one button, current file metadata
(name/size/last-modified), polling the REST metadata endpoint every few
seconds so a save made in Word is visible without a manual refresh.

## Scope / limitations of this concept

- Single file, single user, no auth — this is a proof of concept, not a
  production document-editing service.
- Locking is in-memory in one Spring Boot process; doesn't survive a
  restart and isn't safe across multiple instances.
- Requires desktop MS Word installed locally and the OS having the
  `ms-word:` protocol handler registered. Doesn't work with Word for the
  web or mobile Word.
- No HTTPS/auth on the WebDAV endpoint. A real deployment would need TLS
  (Word can refuse to negotiate over plain HTTP outside localhost) and
  probably Basic/NTLM auth, which is what Office actually expects from an
  authenticated WebDAV share.

## Running it

See `server/CLAUDE.md` and `client/CLAUDE.md` for module-specific run
instructions; the short version:

```bash
# terminal 1
cd server && mvn spring-boot:run

# terminal 2
cd client && npm install && npm run dev
```

Then open `http://localhost:3000` and click "Open in Word" (requires
desktop Word on the machine running the browser).
