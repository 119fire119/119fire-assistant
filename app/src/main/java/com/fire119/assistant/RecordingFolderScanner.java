package com.fire119.assistant;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads audio files from a user-selected SAF tree, including Samsung's nested Call folder. */
public final class RecordingFolderScanner {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VISITED = 10000;

    private RecordingFolderScanner() {}

    public static List<DocumentFile> scan(Context context, Uri treeUri, int maxFiles) throws Exception {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists() || !root.isDirectory() || !root.canRead()) {
            throw new SecurityException("연결한 녹음 폴더를 읽을 수 없습니다. 폴더 권한을 다시 연결해주세요.");
        }
        List<DocumentFile> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        walk(root, 0, visited, found);
        found.sort(Comparator.comparingLong(DocumentFile::lastModified).reversed());
        if (found.size() > maxFiles) return new ArrayList<>(found.subList(0, maxFiles));
        return found;
    }

    private static void walk(DocumentFile directory, int depth, Set<String> visited,
                             List<DocumentFile> found) throws Exception {
        if (depth > MAX_DEPTH || visited.size() >= MAX_VISITED) return;
        String key = directory.getUri().toString();
        if (!visited.add(key)) return;
        DocumentFile[] children = directory.listFiles();
        for (DocumentFile child : children) {
            if (visited.size() >= MAX_VISITED) return;
            if (child.isDirectory()) {
                walk(child, depth + 1, visited, found);
            } else if (child.isFile() && isAudio(child)) {
                found.add(child);
            }
        }
    }

    public static boolean isAudio(DocumentFile file) {
        String mime = file.getType() == null ? "" : file.getType().toLowerCase(java.util.Locale.ROOT);
        String name = file.getName() == null ? "" : file.getName().toLowerCase(java.util.Locale.ROOT);
        return mime.startsWith("audio/") || name.endsWith(".m4a") || name.endsWith(".mp3") ||
                name.endsWith(".wav") || name.endsWith(".amr") || name.endsWith(".3gp") ||
                name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".opus");
    }
}
