/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.filemanager.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.widget.ImageView;

import com.cyanogenmod.filemanager.R;
import com.cyanogenmod.filemanager.model.FileSystemObject;
import com.cyanogenmod.filemanager.ui.ThemeManager.Theme;
import com.cyanogenmod.filemanager.util.FileHelper;
import com.cyanogenmod.filemanager.util.MediaHelper;
import com.cyanogenmod.filemanager.util.MimeTypeHelper;
import com.cyanogenmod.filemanager.util.MimeTypeHelper.KnownMimeTypeResolver;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * A class that holds icons for a more efficient access.
 */
public class IconHolder {

    private static final int MAX_CACHE = 500;

    private static final int MSG_LOAD = 1;
    private static final int MSG_LOADED = 2;

    private final Map<String, Drawable> mIcons;     // Themes based
    private final Map<String, Drawable> mAppIcons;  // App based

    private final WeakHashMap<ImageView, Loadable> mRequests;

    private final Context mContext;
    private final boolean mUseThumbs;

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    public interface ICallback {
        void onPreExecute(ImageView imageView);
        void onLoaded(ImageView imageView, Drawable icon);
    }

    public interface ITransform {
        Drawable transform(Drawable d);
    }

    /**
     * This is kind of a hack, we should have a loadable for each MimeType we run into.
     * TODO: Refactor this to have different loadables
     */
    private static class Loadable {

        private Context mContext;
        private WeakReference<ICallback> mCallback;
        private ITransform mTransform;
        private static boolean sAlbumsDirty = true;
        private static Map<String, Long> sAlbums;

        FileSystemObject fso;
        WeakReference<ImageView> view;
        Drawable result;

        public Loadable(Context context, ImageView view, FileSystemObject fso, ICallback callback,
                        ITransform transform) {
            this.mContext = context.getApplicationContext();
            this.fso = fso;
            this.view = new WeakReference<>(view);
            this.result = null;
            this.mCallback = new WeakReference<>(callback);
            this.mTransform = transform;
        }

        private static synchronized Map<String, Long> getAlbums(Context context) {
            if (sAlbumsDirty) {
                sAlbums = MediaHelper.getAllAlbums(context.getContentResolver());
                sAlbumsDirty = false;
            }
            return sAlbums;
        }

        public static synchronized void dirtyAlbums() {
            sAlbumsDirty = true;
        }

        public boolean load() {
            result = loadDrawable(fso);
            if (mTransform != null) {
                result = mTransform.transform(result);
            }
            return result != null;
        }

        private Drawable loadDrawable(FileSystemObject fso) {
            final String filePath = MediaHelper.normalizeMediaPath(fso.getFullPath());

            if (KnownMimeTypeResolver.isAndroidApp(mContext, fso)) {
                return getAppDrawable(fso);
            } else if (KnownMimeTypeResolver.isImage(mContext, fso)) {
                return getImageDrawable(filePath);
            } else if (KnownMimeTypeResolver.isVideo(mContext, fso)) {
                return getVideoDrawable(filePath);
            } else if (FileHelper.isDirectory(fso)) {
                Map<String, Long> albums = getAlbums(mContext);
                if (albums.containsKey(filePath)) {
                    return getAlbumDrawable(albums.get(filePath));
                }
            }
            return null;
        }

        /**
         * Method that returns the main icon of the app
         *
         * @param fso The FileSystemObject
         * @return Drawable The drawable or null if cannot be extracted
         */
        private Drawable getAppDrawable(FileSystemObject fso) {
            final String filepath = fso.getFullPath();
            PackageManager pm = mContext.getPackageManager();
            PackageInfo packageInfo = pm.getPackageArchiveInfo(filepath,
                    PackageManager.GET_ACTIVITIES);
            if (packageInfo != null) {
                // Read http://code.google.com/p/android/issues/detail?id=9151, CM fixed this
                // issue. We retain it for compatibility with older versions and roms without
                // this fix. Required to access apk which are not installed.
                final ApplicationInfo appInfo = packageInfo.applicationInfo;
                appInfo.sourceDir = filepath;
                appInfo.publicSourceDir = filepath;
                return pm.getDrawable(appInfo.packageName, appInfo.icon, appInfo);
            }
            return null;
        }

        /**
         * Method that returns a thumbnail of the picture
         *
         * @param file The path to the file
         * @return Drawable The drawable or null if cannot be extracted
         */
        private Drawable getImageDrawable(String file) {
            Bitmap thumb = ThumbnailUtils.createImageThumbnail(
                    MediaHelper.normalizeMediaPath(file),
                    ThumbnailUtils.TARGET_SIZE_MINI_THUMBNAIL);
            if (thumb == null) {
                return null;
            }
            // This is terrible, but for now we only ever want a single size icon.
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.circle_icon_wh);
            thumb = ThumbnailUtils.extractThumbnail(thumb, size, size,
                    ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
            if (thumb == null) {
                return null;
            }
            return new BitmapDrawable(mContext.getResources(), thumb);
        }

        /**
         * Method that returns a thumbnail of the video
         *
         * @param file The path to the file
         * @return Drawable The drawable or null if cannot be extracted
         */
        private Drawable getVideoDrawable(String file) {
            Bitmap thumb = ThumbnailUtils.createVideoThumbnail(
                    MediaHelper.normalizeMediaPath(file),
                    ThumbnailUtils.TARGET_SIZE_MICRO_THUMBNAIL);
            if (thumb == null) {
                return null;
            }
            return new BitmapDrawable(mContext.getResources(), thumb);
        }

        /**
         * Method that returns a thumbnail of the album folder
         *
         * @param albumId The album identifier
         * @return Drawable The drawable or null if cannot be extracted
         */
        private Drawable getAlbumDrawable(long albumId) {
            String path = MediaHelper.getAlbumThumbnailPath(mContext.getContentResolver(), albumId);
            if (path == null) {
                return null;
            }
            Bitmap thumb = ThumbnailUtils.createImageThumbnail(path,
                    ThumbnailUtils.TARGET_SIZE_MICRO_THUMBNAIL);
            if (thumb == null) {
                return null;
            }
            return new BitmapDrawable(mContext.getResources(), thumb);
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOADED:
                    processResult((Loadable) msg.obj);
                    break;
            }
        }

        private void processResult(Loadable result) {
            ImageView view = result.view.get();
            if (view == null) {
                return;
            }

            // Cache the new drawable
            final String filePath = MediaHelper.normalizeMediaPath(result.fso.getFullPath());
            if (result.result != null) {
                mAppIcons.put(filePath, result.result);
            }

            Loadable requestedForImageView = mRequests.get(view);
            if (requestedForImageView != result) {
                return;
            }

            if (result.mCallback != null) {
                final ICallback callback = result.mCallback.get();
                if (callback != null) {
                    callback.onLoaded(view, result.result);
                }
            } else {
                view.setImageDrawable(result.result);
            }
        }
    };

    private ContentObserver mMediaObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Loadable.dirtyAlbums();
        }
    };

    /**
     * Constructor of <code>IconHolder</code>.
     *
     * @param useThumbs If thumbs of images, videos, apps, ... should be returned
     * instead of the default icon.
     */
    public IconHolder(Context context, boolean useThumbs) {
        super();
        this.mContext = context;
        this.mUseThumbs = useThumbs;
        this.mRequests = new WeakHashMap<>();
        this.mIcons = new HashMap<>();
        this.mAppIcons = new LinkedHashMap<String, Drawable>(MAX_CACHE, .75F, true) {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Entry<String, Drawable> eldest) {
                return size() > MAX_CACHE;
            }
        };
        if (useThumbs) {
            final ContentResolver cr = mContext.getContentResolver();
            for (Uri uri : MediaHelper.RELEVANT_URIS) {
                cr.registerContentObserver(uri, true, mMediaObserver);
            }
        }
    }

    /**
     * Method that returns a drawable reference of a icon.
     *
     * @param resid The resource identifier
     * @return Drawable The drawable icon reference
     */
    public Drawable getDrawable(final String resid) {
        //Check if the icon exists in the cache
        if (this.mIcons.containsKey(resid)) {
            return this.mIcons.get(resid);
        }

        //Load the drawable, cache and returns reference
        Theme theme = ThemeManager.getCurrentTheme(mContext);
        Drawable dw = theme.getDrawable(mContext, resid);
        this.mIcons.put(resid, dw);
        return dw;
    }

    public void cancel(ImageView iconView) {
        this.mRequests.remove(iconView);
    }

    /**
     * Clearing the selected Icon Cache
     * @param fso The Selected FileSystemObject reference
     */
    public void clearCacheImages(FileSystemObject fso) {
        final String filePath = MediaHelper.normalizeMediaPath(fso.getFullPath());
        if (filePath != null) {
            mAppIcons.remove(filePath);
        }
    }

    /**
     * Method that returns a drawable reference of a FileSystemObject.
     *
     * @param iconView View to load the drawable into
     * @param fso The FileSystemObject reference
     * @param defaultIconId Resource ID to be used in case no specific drawable could be found
     */
    public void loadDrawable(ImageView iconView, FileSystemObject fso, int defaultIconId) {
        loadDrawable(iconView, fso, defaultIconId, null);
    }

    /**
     * Method that returns a drawable reference of a FileSystemObject.
     *
     * @param iconView View to load the drawable into
     * @param fso The FileSystemObject reference
     * @param defaultIconId Resource ID to be used in case no specific drawable could be found
     * @param callback ICallback to use when finished loading
     */
    public void loadDrawable(ImageView iconView, FileSystemObject fso, int defaultIconId,
                             ICallback callback) {
        loadDrawable(iconView, fso, defaultIconId, callback, null);
    }

    /**
     * Method that returns a drawable reference of a FileSystemObject.
     *
     * @param iconView View to load the drawable into
     * @param fso The FileSystemObject reference
     * @param defaultIconId Resource ID to be used in case no specific drawable could be found
     * @param callback ICallback to use when finished loading
     * @param transform The transfromer to apply to the drawable
     */
    public void loadDrawable(ImageView iconView, FileSystemObject fso, int defaultIconId,
                             ICallback callback,  ITransform transform) {
        Drawable icon = null;
        if (callback != null) {
            callback.onPreExecute(iconView);
        }

        if (!mUseThumbs) {
            icon = mContext.getResources().getDrawable(defaultIconId, null);
        } else {
            // Is cached?
            final String filePath = MediaHelper.normalizeMediaPath(fso.getFullPath());
            if (this.mAppIcons.containsKey(filePath)) {
                icon = mAppIcons.get(filePath);
            } else {

                if (mWorkerThread == null) {
                    mWorkerThread = new HandlerThread("IconHolderLoader");
                    mWorkerThread.start();
                    mWorkerHandler = new WorkerHandler(mWorkerThread.getLooper());
                }
                Loadable previousForView = mRequests.get(iconView);
                if (previousForView != null) {
                    mWorkerHandler.removeMessages(MSG_LOAD, previousForView);
                }

                Loadable loadable = new Loadable(mContext, iconView, fso, callback, transform);
                mRequests.put(iconView, loadable);

                mWorkerHandler.obtainMessage(MSG_LOAD, loadable).sendToTarget();
            }
        }
        if ( callback != null) {
            callback.onLoaded(iconView, icon);
        }
    }

    private class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOAD:
                    Loadable l = (Loadable) msg.obj;
                    if (l.load()) {
                        mHandler.obtainMessage(MSG_LOADED, l).sendToTarget();
                    }
                    break;
            }
        }
    }

    /**
     * Shut down worker thread
     */
    private void shutdownWorker() {
        if (mWorkerThread != null) {
            mWorkerThread.getLooper().quit();
            mWorkerHandler = null;
            mWorkerThread = null;
        }
    }

    /**
     * Free any resources used by this instance
     */
    public void cleanup() {
        this.mRequests.clear();
        this.mIcons.clear();
        this.mAppIcons.clear();
        mContext.getContentResolver().unregisterContentObserver(mMediaObserver);
        shutdownWorker();
    }
}
