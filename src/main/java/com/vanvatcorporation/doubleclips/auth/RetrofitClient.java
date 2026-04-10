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
    private static RetrofitClient instance;
    private Retrofit retrofit;

    private RetrofitClient() {
        FileCookieJar cookieJar = new FileCookieJar(StorageHelper.getAuthFile());

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
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
        return retrofit.create(ApiService.class);
    }

    public void clearCookies() {
        if (retrofit.callFactory() instanceof OkHttpClient) {
            CookieJar jar = ((OkHttpClient) retrofit.callFactory()).cookieJar();
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

        public void clear() {
            cookieStore.clear();
            if (storageFile.exists()) {
                storageFile.delete();
            }
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
            saveCookies();
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
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
