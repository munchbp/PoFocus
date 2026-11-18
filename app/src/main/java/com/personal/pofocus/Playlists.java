package com.personal.pofocus;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads playlists made by other apps (e.g. Musicolet):
 *  - .m3u / .m3u8 playlist files
 *  - playlists saved to the phone's media storage
 * Needs the "Music and audio" permission so the songs listed can be found and played.
 */
final class Playlists {
    private Playlists() {}

    static final class PhonePlaylist {
        final long id;
        final String name;
        PhonePlaylist(long id, String name) { this.id = id; this.name = name; }
    }

    static final class Result {
        final List<Uri> tracks = new ArrayList<>();
        int listed; // how many entries the playlist had
    }

    /** File name without extension, e.g. "My Song" for "My Song.mp3". */
    static String displayName(Context ctx, Uri uri, String fallback) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) return fallback;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------------- M3U files ----------------

    /** Song in the phone's music library: its path and its playable content:// address. */
    private static final class Song {
        final String pathLower;
        final Uri uri;
        Song(String p, Uri u) { pathLower = p; uri = u; }
    }

    @SuppressWarnings("deprecation") // DATA (file path) is still filled in and is the only way to match M3U paths
    private static List<Song> librarySongs(ContentResolver cr) {
        List<Song> songs = new ArrayList<>();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(base,
                new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA},
                null, null, null)) {
            if (c == null) return songs;
            while (c.moveToNext()) {
                String path = c.getString(1);
                if (path == null) continue;
                songs.add(new Song(path.toLowerCase(Locale.ROOT),
                        ContentUris.withAppendedId(base, c.getLong(0))));
            }
        } catch (Exception ignored) {}
        return songs;
    }

    static Result readM3u(Context ctx, Uri file) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        List<String> entries = new ArrayList<>();
        try (InputStream in = cr.openInputStream(file);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.replace("﻿", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                entries.add(line);
            }
        }

        List<Song> library = librarySongs(cr);
        Map<String, Song> byFullPath = new HashMap<>();
        for (Song s : library) byFullPath.put(s.pathLower, s);

        Result res = new Result();
        res.listed = entries.size();
        for (String e : entries) {
            Uri u = resolve(e, library, byFullPath);
            if (u != null) res.tracks.add(u);
        }
        return res;
    }

    private static Uri resolve(String entry, List<Song> library, Map<String, Song> byFullPath) {
        if (entry.startsWith("content://")) return Uri.parse(entry);
        if (entry.startsWith("http://") || entry.startsWith("https://")) return null; // online streams not supported
        String path = entry;
        if (path.startsWith("file://")) {
            path = path.substring(7);
            try { path = URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) {}
        }
        path = path.replace('\\', '/');
        String lower = path.toLowerCase(Locale.ROOT);

        // 1. exact full path
        Song exact = byFullPath.get(lower);
        if (exact != null) return exact.uri;

        // 2. relative path like "../Music/Album/song.mp3": match the end of the path
        String tail = lower;
        while (tail.startsWith("../") || tail.startsWith("./") || tail.startsWith("/")) {
            tail = tail.substring(tail.indexOf('/') + 1);
        }
        if (!tail.isEmpty()) {
            for (Song s : library) if (s.pathLower.endsWith("/" + tail)) return s.uri;
        }

        // 3. same file name anywhere on the device
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (!name.isEmpty()) {
            for (Song s : library) if (s.pathLower.endsWith("/" + name)) return s.uri;
        }
        return null;
    }

    // ---------------- playlists in media storage ----------------

    @SuppressWarnings("deprecation")
    static List<PhonePlaylist> phonePlaylists(Context ctx) {
        List<PhonePlaylist> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME},
                null, null, MediaStore.Audio.Playlists.NAME + " COLLATE NOCASE")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String name = c.getString(1);
                out.add(new PhonePlaylist(c.getLong(0), name == null ? ctx.getString(R.string.playlist) : name));
            }
        } catch (Exception ignored) {}
        return out;
    }

    @SuppressWarnings("deprecation")
    static Result phonePlaylistTracks(Context ctx, long playlistId) {
        Result res = new Result();
        Uri members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        try (Cursor c = ctx.getContentResolver().query(members,
                new String[]{MediaStore.Audio.Playlists.Members.AUDIO_ID},
                null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER)) {
            if (c == null) return res;
            while (c.moveToNext()) {
                res.tracks.add(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
            }
        } catch (Exception ignored) {}
        res.listed = res.tracks.size();
        return res;
    }
}
