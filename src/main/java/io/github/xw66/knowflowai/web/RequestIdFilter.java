package io.github.xw66.knowflowai.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String HEADER="X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied=request.getHeader(HEADER);
        String requestId=supplied!=null && supplied.matches("[A-Za-z0-9._:-]{1,100}") ? supplied : UUID.randomUUID().toString();
        response.setHeader(HEADER,requestId);
        try (MDC.MDCCloseable ignored=MDC.putCloseable("requestId",requestId)) {
            chain.doFilter(request,response);
        }
    }
}
