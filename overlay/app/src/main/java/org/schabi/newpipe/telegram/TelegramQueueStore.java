/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TelegramQueueStore {
    public static final class Item {
        public final String id;
        public final String url;
        public final long chatId;
        public final long createdAt;

        Item(final String id, final String url, final long chatId, final long createdAt) {
            this.id = id;
            this.url = url;
            this.chatId = chatId;
            this.createdAt = createdAt;
        }
    }

    private static final String PREFS = "moataz_dow_telegram_queue";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 50;
    private final SharedPreferences preferences;

    public TelegramQueueStore(final Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Item add(final String url, final long chatId) {
        final List<Item> items = loadMutable();
        final Item item = new Item(String.valueOf(System.currentTimeMillis()),
                url.trim(), chatId, System.currentTimeMillis());
        items.add(0, item);
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        persist(items);
        return item;
    }

    public synchronized List<Item> list() {
        return Collections.unmodifiableList(loadMutable());
    }

    public synchronized void remove(final String id) {
        final List<Item> items = loadMutable();
        items.removeIf(item -> item.id.equals(id));
        persist(items);
    }

    public synchronized void clear() {
        preferences.edit().remove(KEY_ITEMS).apply();
    }

    private List<Item> loadMutable() {
        final List<Item> items = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(preferences.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject object = array.getJSONObject(i);
                items.add(new Item(object.getString("id"), object.getString("url"),
                        object.optLong("chatId", 0), object.optLong("createdAt", 0)));
            }
        } catch (final Exception ignored) {
            preferences.edit().remove(KEY_ITEMS).apply();
        }
        return items;
    }

    private void persist(final List<Item> items) {
        final JSONArray array = new JSONArray();
        for (final Item item : items) {
            final JSONObject object = new JSONObject();
            try {
                object.put("id", item.id);
                object.put("url", item.url);
                object.put("chatId", item.chatId);
                object.put("createdAt", item.createdAt);
                array.put(object);
            } catch (final Exception ignored) {
                // JSONObject writes to an in-memory object and should not fail for primitive values.
            }
        }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
