package com.yilan.memory.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects request-controlled learner selection before a controller can run and
 * binds the sole verified subject to the request for self-service handlers.
 */
public final class SubjectBindingFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_SUBJECT_ATTRIBUTE =
            SubjectBindingFilter.class.getName() + ".authenticatedSubject";

    private static final Set<String> IDENTITY_SELECTOR_KEYS = Set.of(
            "learner", "learner_id", "learnerid", "subject", "subject_id", "subjectid", "user", "user_id", "userid");
    private static final Pattern IDENTITY_PATH = Pattern.compile(
            "(?i)/(?:learners?|subjects?|users?|learner_id|learnerid|subject_id|subjectid|user_id|userid)(?:/|;|$)");
    private static final Pattern IDENTITY_JSON_KEY = Pattern.compile(
            "(?i)\\\"(?:learner|learner_id|learnerid|subject|subject_id|subjectid|user|user_id|userid)\\\"\\s*:");
    private static final Pattern IDENTITY_FORM_KEY = Pattern.compile(
            "(?i)(?:^|[&;])(?:learner|learner_id|learnerid|subject|subject_id|subjectid|user|user_id|userid)(?:=|[&;]|$)");
    private static final Pattern MULTIPART_NAME = Pattern.compile(
            "(?im)^content-disposition\\s*:\\s*form-data\\s*;[^\\r\\n]*?\\bname(?<extended>\\*)?\\s*=\\s*(?:\\\"(?<quoted>[^\\\"]*)\\\"|(?<bare>[^;\\s\\r\\n]*))");
    private static final Set<String> TRACE_HEADERS = Set.of("traceparent", "tracestate", "x-request-id", "x-trace-id");

    private final int maximumRequestBytes;
    private final int maximumTraceBytes;

    public SubjectBindingFilter(int maximumRequestBytes, int maximumTraceBytes) {
        if (maximumRequestBytes < 1 || maximumRequestBytes > 65_536) {
            throw new IllegalArgumentException("maximumRequestBytes");
        }
        if (maximumTraceBytes < 1 || maximumTraceBytes > 4_096) {
            throw new IllegalArgumentException("maximumTraceBytes");
        }
        this.maximumRequestBytes = maximumRequestBytes;
        this.maximumTraceBytes = maximumTraceBytes;
    }

    public static AuthenticatedSubject requireBoundSubject(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        var subject = request.getAttribute(AUTHENTICATED_SUBJECT_ATTRIBUTE);
        if (!(subject instanceof AuthenticatedSubject authenticatedSubject)) {
            throw new IllegalStateException("authenticated subject binding is required");
        }
        return authenticatedSubject;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasForbiddenIdentitySelector(request) || traceHeadersExceedBound(request)) {
            reject(response);
            return;
        }
        var contentLength = request.getContentLengthLong();
        if (contentLength > maximumRequestBytes) {
            reject(response);
            return;
        }
        final Charset bodyCharset;
        try {
            bodyCharset = resolveRequestCharset(request);
        } catch (IllegalArgumentException ignored) {
            reject(response);
            return;
        }
        var body = request.getInputStream().readNBytes(maximumRequestBytes + 1);
        if (body.length > maximumRequestBytes || hasForbiddenIdentitySelector(request, body, bodyCharset)) {
            reject(response);
            return;
        }

        var replayable = new ReplayableRequest(request, body, bodyCharset);
        bindVerifiedSubject(replayable);
        filterChain.doFilter(replayable, response);
    }

    private boolean hasForbiddenIdentitySelector(HttpServletRequest request) {
        try {
            var decodedPath = decodePathComponent(request.getRequestURI());
            if (IDENTITY_PATH.matcher(decodedPath).find() || hasForbiddenMatrixIdentitySelector(decodedPath)) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        var query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return false;
        }
        return hasForbiddenFormIdentitySelector(query);
    }

    private boolean hasForbiddenIdentitySelector(HttpServletRequest request, byte[] body, Charset bodyCharset) {
        final String bodyText;
        try {
            bodyText = decodeBody(body, bodyCharset);
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        if (IDENTITY_JSON_KEY.matcher(bodyText).find() || IDENTITY_FORM_KEY.matcher(bodyText).find()) {
            return true;
        }
        if (looksLikeJson(bodyText) && hasForbiddenJsonIdentitySelector(bodyText)) {
            return true;
        }
        if (hasForbiddenMultipartIdentitySelector(bodyText)) {
            return true;
        }
        return isLikelyFormEncoded(request, bodyText) && hasForbiddenFormIdentitySelector(bodyText);
    }

    private static boolean hasForbiddenFormIdentitySelector(String bodyText) {
        for (var pair : bodyText.split("[&;]", -1)) {
            var separator = pair.indexOf('=');
            var rawName = separator < 0 ? pair : pair.substring(0, separator);
            try {
                if (isIdentitySelectorKeyOrAmbiguouslyEncoded(rawName)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForbiddenJsonIdentitySelector(String bodyText) {
        try (var parser = new JsonFactory().createParser(bodyText)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && isIdentitySelectorKey(parser.currentName())) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return true;
        }
    }

    private static boolean hasForbiddenMatrixIdentitySelector(String path) {
        for (var segment : path.split("/", -1)) {
            var matrixStart = segment.indexOf(';');
            if (matrixStart < 0) {
                continue;
            }
            for (var parameter : segment.substring(matrixStart + 1).split(";", -1)) {
                var separator = parameter.indexOf('=');
                var rawName = separator < 0 ? parameter : parameter.substring(0, separator);
                if (rawName.isBlank()) {
                    continue;
                }
                if (isIdentitySelectorKeyOrAmbiguouslyEncoded(rawName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasForbiddenMultipartIdentitySelector(String bodyText) {
        Matcher matcher = MULTIPART_NAME.matcher(bodyText);
        while (matcher.find()) {
            var rawName = matcher.group("quoted") != null ? matcher.group("quoted") : matcher.group("bare");
            if (rawName == null || rawName.isBlank()) {
                return true;
            }
            if (matcher.group("extended") != null) {
                var encodingPrefix = rawName.indexOf("''");
                if (encodingPrefix < 0) {
                    return true;
                }
                rawName = rawName.substring(encodingPrefix + 2);
            }
            if (isIdentitySelectorKeyOrAmbiguouslyEncoded(rawName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentitySelectorKey(String value) {
        return IDENTITY_SELECTOR_KEYS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static boolean isIdentitySelectorKeyOrAmbiguouslyEncoded(String rawValue) {
        var decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        if (isIdentitySelectorKey(decoded)) {
            return true;
        }
        if (decoded.indexOf('%') < 0) {
            return false;
        }
        try {
            return isIdentitySelectorKey(URLDecoder.decode(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static String decodePathComponent(String rawPath) {
        return URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
    }

    private static boolean looksLikeJson(String bodyText) {
        var index = 0;
        while (index < bodyText.length() && Character.isWhitespace(bodyText.charAt(index))) {
            index++;
        }
        if (index < bodyText.length() && bodyText.charAt(index) == '\ufeff') {
            index++;
            while (index < bodyText.length() && Character.isWhitespace(bodyText.charAt(index))) {
                index++;
            }
        }
        return index < bodyText.length() && (bodyText.charAt(index) == '{' || bodyText.charAt(index) == '[');
    }

    private static boolean isLikelyFormEncoded(HttpServletRequest request, String bodyText) {
        var contentType = normalizedContentType(request.getContentType());
        return contentType.equals("application/x-www-form-urlencoded")
                || bodyText.indexOf('=') >= 0 || bodyText.indexOf('&') >= 0 || bodyText.indexOf(';') >= 0;
    }

    private static String normalizedContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        var separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
    }

    private static Charset resolveRequestCharset(HttpServletRequest request) {
        var encoding = request.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported request charset", exception);
        }
    }

    private static String decodeBody(byte[] body, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("malformed request body encoding", exception);
        }
    }

    private boolean traceHeadersExceedBound(HttpServletRequest request) {
        for (var header : TRACE_HEADERS) {
            Enumeration<String> values = request.getHeaders(header);
            while (values != null && values.hasMoreElements()) {
                if (values.nextElement().getBytes(StandardCharsets.UTF_8).length > maximumTraceBytes) {
                    return true;
                }
            }
        }
        return false;
    }

    private void bindVerifiedSubject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedSubject subject) {
            request.setAttribute(AUTHENTICATED_SUBJECT_ATTRIBUTE, subject);
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final Map<String, String[]> parameters;
        private final Charset charset;

        private ReplayableRequest(HttpServletRequest request, byte[] body, Charset charset) {
            super(request);
            this.body = body.clone();
            this.charset = charset;
            this.parameters = buildReplayableParameters(request, this.body, charset);
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        @Override
        public String getParameter(String name) {
            var values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            var values = parameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            var copy = new LinkedHashMap<String, String[]>();
            parameters.forEach((name, values) -> copy.put(name, values.clone()));
            return Collections.unmodifiableMap(copy);
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private static Map<String, String[]> buildReplayableParameters(
                HttpServletRequest request,
                byte[] body,
                Charset charset) {
            var valuesByName = new LinkedHashMap<String, ArrayList<String>>();
            appendFormParameters(request.getQueryString(), valuesByName, StandardCharsets.UTF_8);
            var bodyText = decodeBody(body, charset);
            if (isLikelyFormEncoded(request, bodyText)) {
                appendFormParameters(bodyText, valuesByName, charset);
            }
            var parameters = new LinkedHashMap<String, String[]>();
            valuesByName.forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
            return Collections.unmodifiableMap(parameters);
        }

        private static void appendFormParameters(
                String source,
                Map<String, ArrayList<String>> valuesByName,
                Charset charset) {
            if (source == null || source.isBlank()) {
                return;
            }
            for (var pair : source.split("[&;]", -1)) {
                var separator = pair.indexOf('=');
                var rawName = separator < 0 ? pair : pair.substring(0, separator);
                var rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                var name = URLDecoder.decode(rawName, charset);
                var value = URLDecoder.decode(rawValue, charset);
                valuesByName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
    }
}
