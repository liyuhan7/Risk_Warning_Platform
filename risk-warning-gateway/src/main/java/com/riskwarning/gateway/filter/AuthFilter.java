package com.riskwarning.gateway.filter;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.riskwarning.common.result.Result;
import com.riskwarning.common.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class AuthFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUtils jwtUtils;


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 放行无需认证的路径
        String path = exchange.getRequest().getURI().getPath();
        if (path.contains("/login") || path.contains("/register")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少或错误的Token");
        }

        String token = authHeader.substring(7);

        if (!jwtUtils.validateToken(token)) {
            return unauthorized(exchange, "Token无效或已过期");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Result<String> result = Result.fail(401, msg);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory().wrap(JSON.toJSONString(result, JSONWriter.Feature.WriteNulls).getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
