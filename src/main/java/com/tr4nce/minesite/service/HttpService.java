package com.tr4nce.minesite.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tr4nce.minesite.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class HttpService {
    public static Logger logger = LoggerFactory.getLogger(HttpService.class);
    private HttpServer server;

    public void startServer() {
        try {
            Integer port = Config.SERVER_PORT.get();
            server = HttpServer.create(new java.net.InetSocketAddress(port), 0);

            // 注册路由处理程序
            server.createContext("/api/open", new PostOnlyHandler());
            server.createContext("/api/close", new PostOnlyHandler());
            server.createContext("/api/refresh", new PostOnlyHandler());

            server.start();
            logger.info("HTTP Server started on port {}", port);
        } catch (Exception e) {
            logger.error("Failed to start HTTP Server", e);
        }
    }
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            logger.info("HTTP Server stopped");
        }
    }

    private static class PostOnlyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException{
            String response = "{\"error\":\"Internal server error\"}";
            int status = 500;

            try {
                // 检查请求方法
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    response = "{\"error\":\"Method not allowed. Only POST requests are accepted.\"}";
                    status = 405;
                } else {
                    // 处理POST请求
                    response = handlePostRequest(exchange);
                    status = 200;
                }
            } catch (Exception e) {
                logger.error("Error processing request", e);
                response = "{\"error\":\"" + e.getMessage() + "\"}";
                status = 400;
            } finally {
                // 设置响应头
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

                // 发送响应
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }

        private String handlePostRequest(HttpExchange exchange) {
            // 读取请求体
            String requestBody = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            logger.debug("Received POST request: {}", requestBody);

            JsonObject json;
            try {
                json = JsonParser.parseString(requestBody).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                throw new IllegalArgumentException("Invalid JSON format");
            }
            var uri = exchange.getRequestURI().getPath();
            String name;
            int delay;
            switch (uri){
                case "/api/open":
                    // 处理 /api/open 路径的请求
                    name = json.get("name").getAsString();
                    delay = json.get("delay").getAsInt();
                    MineSiteRefreshService.scheduleSiteOpenOrClose(name,true, delay);
                    // 这里可以添加打开 URL 的逻辑
                    return "{\"status\":\"success\", \"message\":\"Opened Site " + name + " with delay " + delay + "\"}";
                case "/api/close":
                    // 处理 /api/close 路径的请求
                    name = json.get("name").getAsString();
                    delay = json.get("delay").getAsInt();
                    MineSiteRefreshService.scheduleSiteOpenOrClose(name,false, delay);
                    return "{\"status\":\"success\", \"message\":\"Closed Site " + name + " with delay " + delay + "\"}";
                case "/api/refresh":
                    // 处理 /api/status 路径的请求
                    name = json.get("name").getAsString();
                    delay = json.get("delay").getAsInt();
                    MineSiteRefreshService.scheduleRefreshWithDelay(name, delay);
                    return "{\"status\":\"success\", \"message\":\"Refreshed Site " + name + " with delay " + delay + "\"}";
                default:
                    throw new IllegalArgumentException("Unknown API endpoint: " + uri);
            }
        }
    }
}
