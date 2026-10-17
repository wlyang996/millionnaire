package com.millionnaire.gateway.diagnostics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Connection metadata only; never log query strings, request bodies or authentication headers. */
@Component
public class ConnectionDiagnostics extends OncePerRequestFilter {
    public static final String HEADER = "X-MN-Trace-Id";
    private static final Logger log = LoggerFactory.getLogger(ConnectionDiagnostics.class);

    public static String traceId(String supplied) {
        return supplied != null && supplied.matches("[a-zA-Z0-9-]{8,64}") ? supplied : "server-" + UUID.randomUUID();
    }

    public static String trace() { return MDC.get("mnTrace"); }

    /** Only exception classes are safe: HTTP exception messages may include a credential-bearing URL. */
    public static String failureKind(Throwable error) {
        Throwable cause = error;
        for (int i = 0; i < 8 && cause.getCause() != null && cause.getCause() != cause; i++) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = traceId(request.getHeader(HEADER)), previous = trace();
        long started = System.nanoTime();
        boolean failed = false;
        MDC.put("mnTrace", trace);
        response.setHeader(HEADER, trace);
        // Paths supplied by clients may contain arbitrary segments. Use a route category, not raw URLs.
        String route = route(request.getServletPath());
        log.info("[MN-NET] http.start trace={} method={} route={}", trace, request.getMethod(), route);
        try { chain.doFilter(request, response); }
        catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            log.warn("[MN-NET] http.fail trace={} kind={}", trace, failureKind(e));
            throw e;
        } finally {
            log.info("[MN-NET] http.end trace={} route={} status={} elapsedMs={}", trace, route,
                    failed ? 500 : response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            if (previous == null) MDC.remove("mnTrace"); else MDC.put("mnTrace", previous);
        }
    }

    private static String route(String path) {
        for (String exact : new String[]{"/api/auth/methods", "/api/auth/wx-login", "/api/auth/test-login", "/api/me", "/api/me/profile", "/api/boards"}) {
            if (exact.equals(path)) return exact;
        }
        return "/api/other";
    }
}
