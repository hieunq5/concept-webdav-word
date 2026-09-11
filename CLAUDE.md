# attachment-inline-editor

Concept project: open a `.docx` file directly in desktop MS Word from a web
page, edit it, and have Word's own Save write the change straight back to a
backend service — no manual download/upload. See `PROMPT.md` for the full
design rationale (why this requires WebDAV, not just a REST upload/download
API).

## Modules

- `server/` — Spring Boot 3 (Java 17). REST API + a minimal WebDAV endpoint
  that desktop Word talks to directly. See `server/CLAUDE.md`.
- `client/` — Next.js (App Router, TypeScript). One page, one button that
  opens Word via the `ms-word:ofe|u|<url>` protocol handler. See
  `client/CLAUDE.md`.

They're independent Maven/npm projects, not a monorepo tool (no shared
package manager workspace) — run each with its own toolchain.

## Running both

```bash
cd server && mvn spring-boot:run    # http://localhost:8080
cd client && npm install && npm run dev  # http://localhost:3000
```

Client talks to the server via `NEXT_PUBLIC_API_BASE_URL` (defaults to
`http://localhost:8080`, see `client/.env.local.example`). CORS on the
server is restricted to `app.cors.allowed-origins` in
`server/src/main/resources/application.yml` (defaults to
`http://localhost:3000`).

## Things worth knowing before touching this code

- **The WebDAV endpoint is a raw `HttpServlet`, not a `@RestController`.**
  Spring MVC's `RequestMethod` enum has no `PROPFIND`/`LOCK`/`UNLOCK` —
  those HTTP verbs literally cannot be routed through `@RequestMapping`.
  `WebDavServlet` is registered directly via `ServletRegistrationBean` at
  `/webdav/*`, which the servlet container matches ahead of
  DispatcherServlet's `/` mapping.
- **Spring Boot's global `FormContentFilter` will corrupt WebDAV `PUT`
  bodies if left enabled.** It applies to every servlet in the app (not
  just Spring MVC) and tries to parse any PUT/PATCH body sent without an
  explicit `Content-Type` as `application/x-www-form-urlencoded`. Real Word
  clients always set a proper Content-Type, but it's disabled in
  `application.yml` (`spring.mvc.formcontent.filter.enabled: false`)
  defensively — re-enabling it will silently break file uploads that don't
  set a Content-Type.
- **File storage is a plain external directory, not `src/main/resources`.**
  Classpath resources are read-only once the app is packaged; the bundled
  `server/src/main/resources/documents/sample.docx` is only ever the seed
  copy, copied out to `app.documents.dir` (`./data/documents` by default)
  on first startup. That runtime directory is gitignored.
- **Locking is in-memory (`LockManager`), single instance only.** Fine for
  a concept; would need a shared store (Redis, DB row) to survive a
  restart or run more than one server instance.
- This repo currently has no `npm`/internal registry mirror guarantee for
  arbitrary exact package versions — if `npm install` 404s on a pinned
  version, resolve with the registry's actual `latest`/version tags rather
  than guessing an older pin (see `client/package.json` history: it's
  pinned to whatever the mirror actually had cached, not necessarily the
  newest upstream release).

## Testing this concept end-to-end

Automated tests don't cover the actual "does Word open and save back"
behavior — that can only be verified with real desktop Word on Windows or
Mac. To verify the server side without Word, exercise the WebDAV verbs with
curl (LOCK → PUT with the returned lock token → UNLOCK → GET); see the LOCK
example in `server/CLAUDE.md`.
