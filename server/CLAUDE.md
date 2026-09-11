# server (attachment-editor-server)

Spring Boot 3 / Java 17 service that does two unrelated-looking things over
the same file storage:

1. A plain REST API for getting/listing/uploading a `.docx` file.
2. A minimal WebDAV endpoint that desktop MS Word talks to directly so its
   own Save writes changes back here. See root `PROMPT.md` for why this
   needs to be WebDAV at all.

## Layout

```
src/main/java/com/mgm/attachmenteditor/
  AttachmentEditorApplication.java   Spring Boot entrypoint
  api/DocumentController.java        REST API (/api/documents/**)
  webdav/WebDavServlet.java          WebDAV endpoint (/webdav/**)
  webdav/LockManager.java            In-memory WebDAV lock table
  service/DocumentStorageService.java  File I/O against app.documents.dir
  config/WebDavConfig.java           Registers WebDavServlet as a raw servlet
  config/WebConfig.java              CORS for /api/**
src/main/resources/
  application.yml
  documents/sample.docx              Seed file, copied out on first run
```

## Running

```bash
mvn spring-boot:run
```

Starts on `:8080`. On first run it creates `./data/documents/` (gitignored,
relative to wherever you run the process from) and seeds it with a copy of
`src/main/resources/documents/sample.docx`.

## REST API

- `GET /api/documents` — list files with metadata.
- `GET /api/documents/{filename}` — download.
- `GET /api/documents/{filename}/meta` — metadata only (what the client
  polls for the "last modified" display).
- `POST /api/documents/{filename}` — multipart upload (`file` field),
  overwrites.

## WebDAV endpoint (`/webdav/{filename}`)

Implements WebDAV class 2 — just enough for Word's open/edit/autosave flow,
not a general-purpose WebDAV server:

- `OPTIONS` — advertises `DAV: 1,2` and `MS-Author-Via: DAV`. This is the
  header pair Word checks to decide "WebDAV-editable" vs. "plain download."
- `GET` / `HEAD` — serve the file with the docx MIME type, `ETag`,
  `Last-Modified`.
- `PROPFIND` — minimal multistatus response (file or collection-root
  depth-0/1); enough for Word's resource discovery, not a full DAV property
  set.
- `LOCK` / `UNLOCK` — exclusive write locks, in-memory (`LockManager`),
  default 10-minute timeout, refreshable via the `If` header. `PUT` against
  a file locked by someone else returns `423 Locked`.
- `PUT` — writes the body to storage; `204` if the file existed, `201` if
  new.

No `MKCOL`/`COPY`/`MOVE`/`DELETE` — out of scope for a single fixed file.

### Manually exercising WebDAV without Word

```bash
DOCX=src/main/resources/documents/sample.docx
DOCX_MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# 1. OPTIONS — check DAV headers
curl -i -X OPTIONS http://localhost:8080/webdav/sample.docx

# 2. LOCK — grab the token from the Lock-Token response header
curl -i -X LOCK -H "Timeout: Second-600" http://localhost:8080/webdav/sample.docx

# 3. PUT with the token (PUT without it returns 423 if locked)
curl -X PUT -H "Content-Type: $DOCX_MIME" -H "If: (<opaquelocktoken:...>)" \
  --data-binary "@$DOCX" http://localhost:8080/webdav/sample.docx

# 4. UNLOCK
curl -X UNLOCK -H "Lock-Token: <opaquelocktoken:...>" http://localhost:8080/webdav/sample.docx
```

Note `curl --data-binary` without an explicit `-H "Content-Type: ..."`
defaults to `application/x-www-form-urlencoded`, which will 500 — see the
`FormContentFilter` gotcha in the root `CLAUDE.md`. Always set Content-Type
explicitly when testing PUT with curl.

## Gotchas specific to this module

- **`spring.mvc.formcontent.filter.enabled: false` in `application.yml` is
  load-bearing.** Spring Boot's `FormContentFilter` runs ahead of every
  servlet in the context, not just Spring MVC's dispatcher, and tries to
  URL-decode any PUT/PATCH body sent without an explicit Content-Type. Left
  enabled, it throws on the binary docx bytes before `WebDavServlet` ever
  sees the request. Don't re-enable it without scoping it away from
  `/webdav/**`.
- **`WebDavServlet`'s private handler methods are named `handleX`, not
  `doX`.** `HttpServlet` already declares `protected void doPut(...)` etc.;
  naming a private method `doPut` with the same signature is a compile
  error ("attempting to assign weaker access privileges"), not a silent
  override. If you add a new verb handler, avoid the `do*` prefix for the
  same reason.
- **`DocumentStorageService.SAFE_FILENAME` only allows
  `[A-Za-z0-9._-]+\.docx`.** Deliberately tight — the file name comes
  straight from the URL path for both the REST API and WebDAV, so this is
  the path-traversal guard. Loosening it needs an explicit call on why.
