package com.vanvatcorporation.doubleclips.auth;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    private static final String BASE_URL = "https://account.vanvatcorp.com";
    private static final String APP_BASE_URL = "https://app.vanvatcorp.com";
    private static final String AUTH_COOKIE = "authToken";
    private static RetrofitClient instance;
    private Retrofit authRetrofit;
    private Retrofit appRetrofit;
    private final FileCookieJar cookieJar;

    private RetrofitClient() {
        cookieJar = new FileCookieJar(StorageHelper.getAuthFile());

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        authRetrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();

        appRetrofit = new Retrofit.Builder()
                .baseUrl(APP_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();
    }

    public static synchronized RetrofitClient getInstance() {
        if (instance == null) {
            instance = new RetrofitClient();
        }
        return instance;
    }

    public ApiService getApi() {
        return authRetrofit.create(ApiService.class);
    }

    public TemplateApiService getTemplateApi() {
        return appRetrofit.create(TemplateApiService.class);
    }

    /** The account's JWT (cookie "authToken"), which Van Vat Drive takes as a Bearer token; null when signed out. */
    public String getAuthToken() {
        return cookieJar.findValue(AUTH_COOKIE);
    }

    /** Stores a refreshed JWT the server sent (X-Refreshed-Token) in place of the old one. */
    public void replaceAuthToken(String token) {
        cookieJar.replaceValue(AUTH_COOKIE, token);
    }

    public void clearCookies() {
        if (authRetrofit.callFactory() instanceof OkHttpClient) {
            CookieJar jar = ((OkHttpClient) authRetrofit.callFactory()).cookieJar();
            if (jar instanceof FileCookieJar) {
                ((FileCookieJar) jar).clear();
            }
        }
    }

    private static class FileCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        private final File storageFile;
        private final Gson gson;

        public FileCookieJar(File storageFile) {
            this.storageFile = storageFile;
            this.gson = new Gson();
            loadCookies();
        }

        /** The value of the named cookie as stored for the account host first, else any host; null when there is none. */
        public synchronized String findValue(String name) {
            List<Cookie> preferred = cookieStore.get("account.vanvatcorp.com");
            String v = valueIn(preferred, name);
            if (v != null) return v;
            for (List<Cookie> list : cookieStore.values()) {
                v = valueIn(list, name);
                if (v != null) return v;
            }
            return null;
        }

        private static String valueIn(List<Cookie> list, String name) {
            if (list == null) return null;
            for (Cookie c : list) if (c.name().equals(name) && !c.value().isEmpty()) return c.value();
            return null;
        }

        /** Replaces the value of every cookie with this name, keeping its other attributes, and saves. */
        public synchronized void replaceValue(String name, String value) {
            boolean changed = false;
            for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                List<Cookie> rebuilt = new ArrayList<>();
                for (Cookie c : entry.getValue()) {
                    if (c.name().equals(name)) {
                        Cookie.Builder b = new Cookie.Builder().name(name).value(value).expiresAt(c.expiresAt()).path(c.path());
                        if (c.secure()) b.secure();
                        if (c.httpOnly()) b.httpOnly();
                        if (c.hostOnly()) b.hostOnlyDomain(c.domain()); else b.domain(c.domain());
                        rebuilt.add(b.build());
                        changed = true;
                    } else {
                        rebuilt.add(c);
                    }
                }
                entry.setValue(rebuilt);
            }
            if (changed) saveCookies();
        }

        public synchronized void clear() {
            cookieStore.clear();
            if (storageFile.exists()) {
                storageFile.delete();
            }
        }

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
            saveCookies();
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }

        private void saveCookies() {
            try (FileWriter writer = new FileWriter(storageFile)) {
                Map<String, List<SerializableCookie>> serializableStore = new HashMap<>();
                for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                    List<SerializableCookie> list = new ArrayList<>();
                    for (Cookie c : entry.getValue()) {
                        list.add(new SerializableCookie(c));
                    }
                    serializableStore.put(entry.getKey(), list);
                }
                gson.toJson(serializableStore, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void loadCookies() {
            if (!storageFile.exists()) return;
            try (FileReader reader = new FileReader(storageFile)) {
                Type type = new TypeToken<Map<String, List<SerializableCookie>>>() {}.getType();
                Map<String, List<SerializableCookie>> serializableStore = gson.fromJson(reader, type);
                if (serializableStore != null) {
                    for (Map.Entry<String, List<SerializableCookie>> entry : serializableStore.entrySet()) {
                        List<Cookie> cookies = new ArrayList<>();
                        for (SerializableCookie sc : entry.getValue()) {
                            cookies.add(sc.toCookie());
                        }
                        cookieStore.put(entry.getKey(), cookies);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class SerializableCookie {
        String name;
        String value;
        long expiresAt;
        String domain;
        String path;
        boolean secure;
        boolean httpOnly;
        boolean hostOnly;

        public SerializableCookie(Cookie cookie) {
            this.name = cookie.name();
            this.value = cookie.value();
            this.expiresAt = cookie.expiresAt();
            this.domain = cookie.domain();
            this.path = cookie.path();
            this.secure = cookie.secure();
            this.httpOnly = cookie.httpOnly();
            this.hostOnly = cookie.hostOnly();
        }

        public Cookie toCookie() {
            Cookie.Builder builder = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .expiresAt(expiresAt)
                    .path(path);

            if (secure) builder.secure();
            if (httpOnly) builder.httpOnly();
            if (hostOnly) builder.hostOnlyDomain(domain);
            else builder.domain(domain);

            return builder.build();
        }
    }
}
