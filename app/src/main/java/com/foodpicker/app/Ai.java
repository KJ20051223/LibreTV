package com.foodpicker.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

/** 原生侧的 HTTP 和 AI 识别调用（自动采集在后台用，不依赖网页）。 */
final class Ai {

    private Ai() {
    }

    /** 返回 {状态码, 响应体}；网络异常时状态码为 0、响应体为错误信息。 */
    static Object[] post(String url, JSONObject headers, String body) {
        int status = 0;
        String text;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                conn.setRequestProperty(k, headers.getString(k));
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            text = is == null ? "" : new String(readBytes(is), StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new Object[]{status, text};
    }

    static byte[] readBytes(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * 调一次模型。cfg 来自网页：fmt(anthropic/openai)、url、key、model、textModel。
     * images 为 data URL 列表（可为空，表示纯文字请求）。失败时抛出带原因的异常。
     */
    static String ask(JSONObject cfg, List<String> images, String prompt) throws Exception {
        boolean anthropic = "anthropic".equals(cfg.optString("fmt"));
        String model = images.isEmpty() ? cfg.optString("textModel", cfg.optString("model")) : cfg.optString("model");
        JSONObject headers = new JSONObject();
        JSONObject body = new JSONObject().put("model", model);
        JSONArray content = new JSONArray();
        if (anthropic) {
            headers.put("content-type", "application/json")
                    .put("x-api-key", cfg.optString("key"))
                    .put("anthropic-version", "2023-06-01")
                    .put("anthropic-beta", "server-side-fallback-2026-07-01");
            for (String d : images) {
                int comma = d.indexOf(',');
                content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", d.substring(comma + 1))));
            }
            content.put(new JSONObject().put("type", "text").put("text", prompt));
            body.put("max_tokens", 16000).put("fallbacks", "default");
        } else {
            headers.put("Content-Type", "application/json").put("Authorization", "Bearer " + cfg.optString("key"));
            for (String d : images) {
                content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", d)));
            }
            content.put(new JSONObject().put("type", "text").put("text", prompt));
        }
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                .put("content", images.isEmpty() && !anthropic ? (Object) prompt : content)));

        Object[] r = post(cfg.optString("url"), headers, body.toString());
        int status = (Integer) r[0];
        String text = (String) r[1];
        if (status == 0) throw new Exception("网络错误：" + text);
        JSONObject j;
        try {
            j = new JSONObject(text);
        } catch (Exception e) {
            throw new Exception("HTTP " + status + "：" + cut(text));
        }
        if (status >= 400) {
            Object err = j.opt("error");
            String msg = err instanceof JSONObject ? ((JSONObject) err).optString("message", err.toString()) : String.valueOf(err != null ? err : text);
            throw new Exception("HTTP " + status + "：" + cut(msg));
        }
        StringBuilder sb = new StringBuilder();
        if (anthropic) {
            if ("refusal".equals(j.optString("stop_reason"))) throw new Exception("模型拒绝了这次请求");
            JSONArray blocks = j.optJSONArray("content");
            for (int i = 0; blocks != null && i < blocks.length(); i++) {
                JSONObject b = blocks.getJSONObject(i);
                if ("text".equals(b.optString("type"))) sb.append(b.optString("text")).append('\n');
            }
        } else {
            JSONArray choices = j.optJSONArray("choices");
            JSONObject msg = choices != null && choices.length() > 0 ? choices.getJSONObject(0).optJSONObject("message") : null;
            Object c = msg == null ? null : msg.opt("content");
            if (c instanceof JSONArray) {
                for (int i = 0; i < ((JSONArray) c).length(); i++) sb.append(((JSONArray) c).getJSONObject(i).optString("text")).append('\n');
            } else if (c != null) {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) throw new Exception("模型没有返回内容");
        return out;
    }

    private static String cut(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
