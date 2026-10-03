package com.wenxian.admin;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

public class AdminAuthFilter implements Filter {

    public static final String ADMIN_USER_ID_ATTRIBUTE = "wenxian.adminUserId";

    private final JdbcTemplate jdbc;

    public AdminAuthFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String uri = req.getRequestURI();
        if (uri.endsWith("/api/admin/login")) {
            chain.doFilter(request, response);
            return;
        }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"未提供管理令牌\"}");
            return;
        }

        String tokenHash = sha256(auth.substring(7));
        java.util.List<String> userIds = jdbc.query(
                """
                        SELECT u.id
                        FROM admin_session s
                        JOIN admin_user u ON u.id = s.user_id
                        WHERE s.token_hash = ? AND s.expires_at > ? AND u.status = 'ACTIVE'
                        """,
                (row, index) -> row.getString("id"), tokenHash, Timestamp.from(Instant.now()));
        if (userIds.isEmpty()) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"管理令牌无效或已过期\"}");
            return;
        }

        req.setAttribute(ADMIN_USER_ID_ATTRIBUTE, userIds.getFirst());
        chain.doFilter(request, response);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
