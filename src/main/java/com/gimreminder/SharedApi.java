package com.gimreminder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class SharedApi
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Gson gson;
    private final OkHttpClient http;

    SharedApi(OkHttpClient http, Gson gson)
    {
        this.http = http.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build();
        this.gson = gson;
    }

    JsonObject health(String base) throws IOException
    {
        HttpUrl url = serverUrl(base).newBuilder().addPathSegment("health").build();
        try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute())
        {
            if (response.body() == null) throw new IOException("Server did not respond");
            String body = response.body().string();
            if (!response.isSuccessful()) throw new IOException("Server returned " + response.code() + ". Check the Worker URL.");
            JsonObject result;
            try { result = gson.fromJson(body, JsonObject.class); }
            catch (RuntimeException ex) { throw new IOException("The Server URL did not return a valid GIM response", ex); }
            if (result == null || !result.has("service") || !"gim-item-reminder".equals(result.get("service").getAsString()))
                throw new IOException("This server is not running the updated GIM Item Reminder backend. The shared service needs the updated v0.5 backend.");
            if (!result.has("api_version") || result.get("api_version").getAsInt() < 5)
                throw new IOException("Your GIM server is outdated. The shared service needs the updated v0.5 backend.");
            return result;
        }
    }

    private static HttpUrl serverUrl(String base) throws IOException
    {
        if (base == null || base.trim().isEmpty()) throw new IOException("Server URL is missing");
        HttpUrl url = HttpUrl.parse(base.trim());
        if (url == null || !"https".equals(url.scheme()))
            throw new IOException("Enter a valid HTTPS Server URL (the Cloudflare Worker address)");
        return url;
    }

    JsonObject call(String base, String code, String method, String route, Map<String, Object> fields) throws IOException
    {
        HttpUrl url = serverUrl(base);
        HttpUrl.Builder builder = url.newBuilder();
        for (String segment : route.split("/"))
        {
            if (!segment.isEmpty()) builder.addPathSegment(segment);
        }
        if ("GET".equals(method)) builder.addQueryParameter("code", code);
        Request.Builder request = new Request.Builder().url(builder.build()).header("X-GIM-Group-Code", code);
        if (!"GET".equals(method))
        {
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            if (!payload.containsKey("code")) payload.put("code", code);
            request.method(method, RequestBody.create(JSON, gson.toJson(payload)));
        }
        try (Response response = http.newCall(request.build()).execute())
        {
            if (response.body() == null) throw new IOException("Empty server response");
            String body = response.body().string();
            if (!response.isSuccessful())
            {
                String detail = "";
                try
                {
                    JsonObject errorBody = gson.fromJson(body, JsonObject.class);
                    if (errorBody != null && errorBody.has("error")) detail = errorBody.get("error").getAsString();
                }
                catch (RuntimeException ignored) { /* Server may have returned HTML */ }
                
                throw new IOException("Server error " + response.code() + (detail.isEmpty() ? ". Check the backend deployment." : ": " + detail));
            }
            try { return gson.fromJson(body, JsonObject.class); }
            catch (RuntimeException ex) { throw new IOException("Server sent an invalid response", ex); }
        }
    }

    static Map<String, Object> payload(Object... values)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) payload.put((String) values[i], values[i + 1]);
        return payload;
    }

    JsonObject discord(String base, String code, String method, String route,
                       String creatorToken, Map<String, Object> fields) throws IOException
    {
        HttpUrl.Builder url = serverUrl(base).newBuilder();
        for (String segment : route.split("/")) if (!segment.isEmpty()) url.addPathSegment(segment);
        Map<String, Object> payload = new LinkedHashMap<>(fields);
        payload.put("code", code);
        Request.Builder request = new Request.Builder().url(url.build())
            .header("X-GIM-Group-Code", code)
            .header("X-GIM-Creator-Token", creatorToken)
            .method(method, "DELETE".equals(method) ? RequestBody.create(JSON, gson.toJson(payload)) : RequestBody.create(JSON, gson.toJson(payload)));
        try (Response response = http.newCall(request.build()).execute())
        {
            if (response.body() == null) throw new IOException("No response from group service");
            String body = response.body().string();
            JsonObject parsed;
            try { parsed = gson.fromJson(body, JsonObject.class); }
            catch (RuntimeException e) { throw new IOException("Unexpected response from group service", e); }
            if (!response.isSuccessful())
                throw new IOException(parsed != null && parsed.has("error")
                    ? parsed.get("error").getAsString() : "Server error " + response.code());
            return parsed;
        }
    }
}
