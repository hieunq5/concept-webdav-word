package com.mgm.attachmenteditor.webdav;

import com.mgm.attachmenteditor.service.DocumentStorageService;
import com.mgm.attachmenteditor.service.DocumentStorageService.DocumentMeta;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal WebDAV class-2 endpoint (OPTIONS/GET/HEAD/PUT/PROPFIND/LOCK/UNLOCK)
 * that exists for exactly one reason: it lets desktop MS Word open a document
 * straight from the browser via the {@code ms-word:ofe|u|<url>} protocol
 * handler, edit it, and have Word's own "Save" write the change back here
 * over HTTP - no separate save/download step in the client app.
 *
 * <p>Registered as a plain servlet (see WebDavConfig) rather than a
 * {@code @RestController} because Spring MVC's {@code RequestMethod} enum has
 * no PROPFIND/LOCK/UNLOCK - those aren't part of Spring's HTTP verb set, only
 * a raw {@link HttpServlet#service} can dispatch on them.
 */
public class WebDavServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(WebDavServlet.class);

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneOffset.UTC);
    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern LOCK_TOKEN_IN_HEADER = Pattern.compile("opaquelocktoken:[0-9a-fA-F-]+");

    private final DocumentStorageService storage;
    private final LockManager locks = new LockManager();

    public WebDavServlet(DocumentStorageService storage) {
        this.storage = storage;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String method = req.getMethod();
        // Diagnostic logging: this is the only place that sees every request a
        // WebDAV client (Word, or curl) actually sends, including ones our
        // handlers reject - invaluable for figuring out where a real Word
        // client's open/edit flow diverges from what curl-based testing covers.
        log.info("WebDAV <- {} {} (User-Agent=[{}] If=[{}] Depth=[{}] Timeout=[{}] Lock-Token=[{}] Content-Type=[{}])",
                method, req.getRequestURI(), req.getHeader("User-Agent"), req.getHeader("If"),
                req.getHeader("Depth"), req.getHeader("Timeout"), req.getHeader("Lock-Token"),
                req.getContentType());
        try {
            switch (method) {
                case "OPTIONS" -> doOptions(req, resp);
                case "GET" -> handleGetOrHead(req, resp, true);
                case "HEAD" -> handleGetOrHead(req, resp, false);
                case "PUT" -> handlePut(req, resp);
                case "PROPFIND" -> handlePropfind(req, resp);
                case "LOCK" -> handleLock(req, resp);
                case "UNLOCK" -> handleUnlock(req, resp);
                default -> resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, method + " not supported");
            }
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } finally {
            log.info("WebDAV -> {} {} status={}", method, req.getRequestURI(), resp.getStatus());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, PROPFIND, LOCK, UNLOCK");
        // These two headers are what make Office treat the URL as WebDAV-editable
        // instead of falling back to a plain HTTP download.
        resp.setHeader("DAV", "1,2");
        resp.setHeader("MS-Author-Via", "DAV");
        resp.setContentLength(0);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void handleGetOrHead(HttpServletRequest req, HttpServletResponse resp, boolean withBody) throws IOException {
        String filename = filenameFromPath(req);
        if (filename == null || !storage.exists(filename)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        DocumentMeta meta = storage.meta(filename);
        resp.setContentType(DOCX_MIME);
        resp.setContentLengthLong(meta.size());
        resp.setHeader("ETag", meta.etag());
        resp.setHeader("Last-Modified", HTTP_DATE.format(meta.lastModified()));
        resp.setStatus(HttpServletResponse.SC_OK);
        if (withBody) {
            try (var in = storage.read(filename)) {
                in.transferTo(resp.getOutputStream());
            }
        }
    }

    private void handlePut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String filename = filenameFromPath(req);
        if (filename == null) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "PUT requires a file name, not the collection root");
            return;
        }
        String presentedToken = extractToken(req.getHeader("If"));
        if (locks.isLockedByOther(filename, presentedToken)) {
            resp.sendError(423 /* Locked */, "Document is locked by another editor");
            return;
        }
        boolean existedBefore = storage.exists(filename);
        storage.write(filename, req.getInputStream());
        resp.setStatus(existedBefore ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    private void handleLock(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String filename = filenameFromPath(req);
        if (filename == null) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Duration timeout = parseTimeout(req.getHeader("Timeout"));
        String owner = req.getRemoteAddr();

        String presentedToken = extractToken(req.getHeader("If"));
        LockManager.LockInfo info;
        if (presentedToken != null) {
            info = locks.refresh(filename, presentedToken, timeout).orElse(null);
            if (info == null) {
                resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Lock token not found");
                return;
            }
        } else if (locks.isLockedByOther(filename, null)) {
            resp.sendError(423 /* Locked */, "Document is already locked");
            return;
        } else {
            info = locks.lock(filename, owner, timeout);
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setHeader("Lock-Token", "<" + info.token() + ">");
        resp.setContentType("application/xml; charset=UTF-8");
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:prop xmlns:D="DAV:">
                  <D:lockdiscovery>
                    <D:activelock>
                      <D:locktype><D:write/></D:locktype>
                      <D:lockscope><D:exclusive/></D:lockscope>
                      <D:depth>0</D:depth>
                      <D:owner>%s</D:owner>
                      <D:timeout>Second-%d</D:timeout>
                      <D:locktoken><D:href>%s</D:href></D:locktoken>
                    </D:activelock>
                  </D:lockdiscovery>
                </D:prop>
                """.formatted(escapeXml(owner), timeout.toSeconds(), info.token());
        resp.getWriter().write(body);
    }

    private void handleUnlock(HttpServletRequest req, HttpServletResponse resp) {
        String filename = filenameFromPath(req);
        String token = extractToken(req.getHeader("Lock-Token"));
        if (filename != null && token != null) {
            locks.unlock(filename, token);
        }
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void handlePropfind(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String filename = filenameFromPath(req);
        resp.setStatus(207); // Multi-Status
        resp.setContentType("application/xml; charset=UTF-8");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n");

        if (filename != null) {
            if (!storage.exists(filename)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            appendFileResponse(xml, req, storage.meta(filename));
        } else {
            // Depth 0/1 request against the collection root itself.
            appendCollectionResponse(xml, req);
            if (!"0".equals(req.getHeader("Depth"))) {
                for (DocumentMeta meta : storage.list()) {
                    appendFileResponse(xml, req, meta);
                }
            }
        }
        xml.append("</D:multistatus>\n");
        resp.getWriter().write(xml.toString());
    }

    private void appendCollectionResponse(StringBuilder xml, HttpServletRequest req) {
        xml.append("""
                  <D:response>
                    <D:href>%s</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:resourcetype><D:collection/></D:resourcetype>
                        <D:displayname>documents</D:displayname>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                """.formatted(collectionHref(req)));
    }

    private void appendFileResponse(StringBuilder xml, HttpServletRequest req, DocumentMeta meta) {
        xml.append("""
                  <D:response>
                    <D:href>%s</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:displayname>%s</D:displayname>
                        <D:resourcetype/>
                        <D:getcontentlength>%d</D:getcontentlength>
                        <D:getcontenttype>%s</D:getcontenttype>
                        <D:getlastmodified>%s</D:getlastmodified>
                        <D:getetag>%s</D:getetag>
                        <D:supportedlock>
                          <D:lockentry>
                            <D:lockscope><D:exclusive/></D:lockscope>
                            <D:locktype><D:write/></D:locktype>
                          </D:lockentry>
                        </D:supportedlock>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                """.formatted(
                fileHref(req, meta.filename()),
                escapeXml(meta.filename()),
                meta.size(),
                DOCX_MIME,
                HTTP_DATE.format(meta.lastModified()),
                escapeXml(meta.etag())));
    }

    /** Path after the servlet mapping, e.g. "/sample.docx" -> "sample.docx"; null for the collection root. */
    private String filenameFromPath(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            return null;
        }
        return pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    }

    private String collectionHref(HttpServletRequest req) {
        return req.getContextPath() + req.getServletPath() + "/";
    }

    private String fileHref(HttpServletRequest req, String filename) {
        return req.getContextPath() + req.getServletPath() + "/" + filename;
    }

    private String extractToken(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        Matcher m = LOCK_TOKEN_IN_HEADER.matcher(headerValue);
        return m.find() ? m.group() : null;
    }

    private Duration parseTimeout(String timeoutHeader) {
        if (timeoutHeader == null) {
            return DEFAULT_LOCK_TIMEOUT;
        }
        Matcher m = Pattern.compile("Second-(\\d+)").matcher(timeoutHeader);
        if (m.find()) {
            long seconds = Math.min(Long.parseLong(m.group(1)), Duration.ofHours(1).toSeconds());
            return Duration.ofSeconds(seconds);
        }
        return DEFAULT_LOCK_TIMEOUT;
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
