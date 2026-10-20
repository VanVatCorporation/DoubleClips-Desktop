package com.vanvatcorporation.doubleclips.drive;

import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * The OpenEditz client: the part of the Drive API the editor needs, locked to the user's protected OpenEditz folder
 * (iOS: OpenEditzClient). Everything starts from {@link #root()} and only follows children from there, so the editor
 * can never read or write outside OpenEditz, even though the same account owns the rest of the Drive.
 * Every call blocks: run it off the FX thread.
 */
public final class OpenEditzClient {
    private final DriveClient client;
    private volatile DriveNode rootNode;
    /** Ids proven to live under OpenEditz. */
    private final Set<String> inside = ConcurrentHashMap.newKeySet();

    public OpenEditzClient(DriveClient client) { this.client = client; }

    public DriveClient drive() { return client; }

    /** The OpenEditz folder (created on first use). */
    public DriveNode root() throws IOException {
        DriveNode r = rootNode;
        if (r != null) return r;
        synchronized (this) {
            if (rootNode == null) {
                DriveNode node = client.ensureOpenEditz();
                inside.add(node.id);
                rootNode = node;
            }
            return rootNode;
        }
    }

    /** Forget what was learned (after sign-out / another account signs in). */
    public synchronized void reset() {
        rootNode = null;
        inside.clear();
    }

    /** Children of a folder inside OpenEditz (null = the OpenEditz folder itself). */
    public List<DriveNode> list(DriveNode folder) throws IOException {
        DriveNode parent = scoped(folder);
        List<DriveNode> items = client.list(parent.id);
        for (DriveNode n : items) inside.add(n.id);
        return items;
    }

    public DriveNode createFolder(String name, DriveNode folder) throws IOException {
        DriveNode parent = scoped(folder);
        DriveNode node = client.createFolder(parent.id, name);
        inside.add(node.id);
        return node;
    }

    /** Walks / creates OpenEditz/a/b/... and returns the last folder. */
    public DriveNode ensureFolder(String... path) throws IOException {
        DriveNode current = root();
        for (String name : path) {
            DriveNode next = null;
            for (DriveNode child : list(current)) {
                if (child.isFolder && child.name.equalsIgnoreCase(name)) { next = child; break; }
            }
            current = next != null ? next : createFolder(name, current);
        }
        return current;
    }

    /** Resumable upload into a folder inside OpenEditz (null = OpenEditz itself). */
    public DriveNode upload(File file, DriveNode folder, String name, LongConsumer progress, AtomicBoolean cancel) throws IOException {
        DriveNode parent = scoped(folder);
        DriveNode node = ResumableUpload.run(client, file, parent.id, name, null, progress, cancel);
        inside.add(node.id);
        return node;
    }

    /** Downloads into {@code dest}. */
    public void downloadTo(DriveNode node, File dest, LongConsumer progress, AtomicBoolean cancel) throws IOException {
        requireInside(node);
        client.download(node, dest, progress, cancel);
    }

    /** Downloads into the cache (Cache/VanVatDrive/{id}/{name}) and returns the file. */
    public File download(DriveNode node, LongConsumer progress, AtomicBoolean cancel) throws IOException {
        requireInside(node);
        File dir = new File(new File(StorageHelper.getCacheDirectory(), "VanVatDrive"), safeSegment(node.id));
        File dest = new File(dir, safeFileName(node.name));
        client.download(node, dest, progress, cancel);
        return dest;
    }

    public DriveNode rename(DriveNode node, String name) throws IOException {
        requireInside(node);
        if (node.isSystemFolder()) throw new DriveError(403, "system_folder", "This folder cannot be renamed.");
        return client.rename(node, name);
    }

    /** Deletes for good (trash + purge) so the space comes back right away. Folders go with everything inside. */
    public void delete(DriveNode node) throws IOException {
        requireInside(node);
        if (node.isSystemFolder()) throw new DriveError(403, "system_folder", "This folder cannot be deleted.");
        client.trash(node);
        client.purge(node);
        inside.remove(node.id);
    }

    // ---- scope -------------------------------------------------------------------------------------------

    private DriveNode scoped(DriveNode folder) throws IOException {
        if (folder == null) return root();
        requireInside(folder);
        return folder;
    }

    private void requireInside(DriveNode node) throws DriveError {
        if (!inside.contains(node.id)) {
            throw new DriveError(403, "outside_openeditz", "That item is not inside the OpenEditz folder.");
        }
    }

    // ---- names -------------------------------------------------------------------------------------------

    /** A file name safe to create locally: no separators or characters file systems reject, never "." / "..". */
    public static String safeFileName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        return n;
    }

    private static String safeSegment(String id) {
        return id == null ? "item" : id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
