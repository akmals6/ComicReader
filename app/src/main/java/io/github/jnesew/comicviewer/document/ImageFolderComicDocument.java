package io.github.jnesew.comicviewer.document;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;

import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.SeriesMetadata;
import io.github.jnesew.comicviewer.util.NaturalOrder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reads a folder of loose image files as one manga chapter. */
public final class ImageFolderComicDocument implements ComicDocument {
    private static final int MAX_PAGES = 10000;
    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private final ContentResolver resolver;
    private final Uri folderUri;
    private final String title;
    private final long size;
    private final long modified;
    private final List<Page> sourcePages;
    private final List<PageInfo> pageInfo;
    private boolean closed;

    private ImageFolderComicDocument(Context context, Uri uri, DocumentInfo info, List<Page> pages) {
        resolver = context.getContentResolver();
        folderUri = uri;
        title = info.title;
        size = info.size;
        modified = info.modified;
        sourcePages = Collections.unmodifiableList(new ArrayList<>(pages));
        ArrayList<PageInfo> infos = new ArrayList<>(pages.size());
        for (Page page : pages) infos.add(new PageInfo(page.name, 1, 1));
        pageInfo = infos;
    }

    public static ImageFolderComicDocument open(Context context, Uri uri, DocumentInfo info)
            throws IOException {
        if (!DocumentsContract.isTreeUri(uri)) {
            throw new IOException("Select the library folder using Android's folder picker.");
        }
        String id;
        try {
            id = DocumentsContract.getDocumentId(uri);
        } catch (RuntimeException e) {
            try {
                id = DocumentsContract.getTreeDocumentId(uri);
            } catch (RuntimeException ignored) {
                throw new IOException("The selected chapter folder cannot be opened.", e);
            }
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id);
        ArrayList<Page> pages = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children, PROJECTION, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list this chapter's image pages.");
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                String name = val(cursor, nameCol);
                if (!isImage(name, val(cursor, mimeCol))) continue;
                if (pages.size() >= MAX_PAGES) {
                    throw new IOException("This chapter contains too many pages (limit 10,000).");
                }
                String childId = val(cursor, idCol);
                if (!childId.isEmpty()) pages.add(new Page(childId, name,
                        number(cursor, sizeCol), number(cursor, modCol)));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("Access to this chapter folder was denied. Select the library folder again.", e);
        }
        pages.sort((a, b) -> NaturalOrder.INSTANCE.compare(a.name, b.name));
        if (pages.isEmpty()) {
            throw new IOException("No JPG, PNG, WEBP, GIF, or BMP pages were found in this folder.");
        }
        return new ImageFolderComicDocument(context, uri, info, pages);
    }

    @Override public Uri uri() { return folderUri; }
    @Override public String key() { return folderUri.toString(); }
    @Override public String title() { return title; }
    @Override public long documentSize() { return size; }
    @Override public long documentModified() { return modified; }
    @Override public int count() { return sourcePages.size(); }
    @Override public int indexedPages() { return pageInfo.size(); }
    @Override public boolean isIndexComplete() { return true; }
    @Override public boolean suggestedRightToLeft() { return true; }
    @Override public SeriesMetadata seriesMetadata() { return SeriesMetadata.EMPTY; }
    @Override public List<PageInfo> pages() { return Collections.unmodifiableList(pageInfo); }
    @Override public List<PageInfo> snapshotPages() { return new ArrayList<>(pageInfo); }
    @Override public PageInfo page(int index) { return pageInfo.get(index); }

    @Override
    public void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback)
            throws IOException {
        ensureOpen();
        for (int i = 0; i < sourcePages.size(); i++) {
            if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
            Page source = sourcePages.get(i);
            try (InputStream in = openPage(i)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    pageInfo.set(i, new PageInfo(source.name, options.outWidth, options.outHeight));
                }
            }
            if (i == 0 || i % 12 == 0 || i == sourcePages.size() - 1) {
                callback.onProgress(i + 1, sourcePages.size());
                callback.onPagesUpdated();
            }
        }
        callback.onComplete(snapshotPages());
    }

    @Override
    public InputStream openPage(int index) throws IOException {
        ensureOpen();
        try {
            InputStream in = resolver.openInputStream(
                    DocumentsContract.buildDocumentUriUsingTree(folderUri, sourcePages.get(index).id));
            if (in == null) throw new IOException("Cannot open image page " + sourcePages.get(index).name);
            return in;
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("Cannot open image page " + sourcePages.get(index).name, e);
        }
    }

    @Override public void close() { closed = true; }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("This chapter folder is already closed.");
    }

    private static boolean isImage(String name, String mime) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp") ||
                mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static String val(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }
    private static long number(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? -1 : c.getLong(i);
    }

    private static final class Page {
        final String id, name;
        final long size, modified;
        Page(String id, String name, long size, long modified) {
            this.id = id; this.name = name; this.size = size; this.modified = modified;
        }
    }
}