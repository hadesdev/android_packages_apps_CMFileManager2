/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.cyanogenmod.filemanager.controllers;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Environment;
import android.os.storage.StorageVolume;
import android.support.design.widget.NavigationView;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import com.cyanogenmod.filemanager.FileManagerApplication;
import com.cyanogenmod.filemanager.R;
import com.cyanogenmod.filemanager.activities.MainActivity;
import com.cyanogenmod.filemanager.adapters.NavigationDrawerAdapter;
import com.cyanogenmod.filemanager.console.VirtualMountPointConsole;
import com.cyanogenmod.filemanager.model.Bookmark;
import com.cyanogenmod.filemanager.model.Bookmark.BOOKMARK_TYPE;
import com.cyanogenmod.filemanager.model.FileSystemObject;
import com.cyanogenmod.filemanager.model.MountPoint;
import com.cyanogenmod.filemanager.model.NavigationDrawerItem;
import com.cyanogenmod.filemanager.model.NavigationDrawerItem.NavigationDrawerItemType;
import com.cyanogenmod.filemanager.preferences.AccessMode;
import com.cyanogenmod.filemanager.ui.widgets.NavigationView.OnDirectoryChangedListener;
import com.cyanogenmod.filemanager.util.FileHelper;
import com.cyanogenmod.filemanager.util.StorageHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.cyanogenmod.filemanager.model.Bookmark.BOOKMARK_TYPE.SDCARD;
import static com.cyanogenmod.filemanager.model.Bookmark.BOOKMARK_TYPE.USB;

/**
 * NavigationDrawerController. This class is contains logic to add/remove and manage items in
 * the NavigationDrawer which uses android support libraries NavigationView.
 */
public class NavigationDrawerController implements OnDirectoryChangedListener {
    private static final String TAG = NavigationDrawerController.class.getSimpleName();
    private static boolean DEBUG = false;

    private static final String STR_USB = "usb"; // $NON-NLS-1$

    public static final int NAVIGATION_DRAWER_HOME = 1;

    private Context mCtx;
    private NavigationView mNavigationDrawer;
    private NavigationDrawerAdapter mAdapter;
    private List<NavigationDrawerItem> mNavigationDrawerItemList;
    private int mLastRoot;
    private int mCurrentSelection;
    private Map<Integer, Bookmark> mStorageBookmarks;

    public NavigationDrawerController(Context ctx, NavigationView navigationView) {
        mCtx = ctx;
        mNavigationDrawer = navigationView;
        mStorageBookmarks = new HashMap<Integer, Bookmark>();
        mNavigationDrawerItemList = new ArrayList<NavigationDrawerItem>();
        mLastRoot = 0;
        mCurrentSelection = R.id.navigation_item_home;
        ListView listView = (ListView)mNavigationDrawer.findViewById(R.id.navigation_view_listview);
        listView.setOnItemClickListener(((MainActivity)mCtx));
        mAdapter = new NavigationDrawerAdapter(mCtx, mNavigationDrawerItemList);
        listView.setAdapter(mAdapter);
    }

    public void loadNavigationDrawerItems() {
        // clear current special nav drawer items
        removeAllItemsFromDrawer();

        mLastRoot = 0;
        String title = null;
        String summary = null;
        int color = mCtx.getResources().getColor(R.color.default_primary);

        // Determine display mode
        boolean showRoot = FileManagerApplication.getAccessMode().compareTo(AccessMode.SAFE) != 0;
        NavigationDrawerItemType itemType = showRoot ?
                NavigationDrawerItemType.DOUBLE : NavigationDrawerItemType.SINGLE;

        // Load Header
        mNavigationDrawerItemList.add(new NavigationDrawerItem(0, NavigationDrawerItemType.HEADER,
                null, null, 0, color));

        // Load Home and Favorites
        title = mCtx.getResources().getString(R.string.navigation_item_title_home);
        summary = null;
        mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_home,
                NavigationDrawerItemType.SINGLE, title, summary, R.drawable.ic_home, color));
        // TODO: Re-enable Favorites once we have a fragment for it
        //title = mCtx.getResources().getString(R.string.navigation_item_title_favorites);
        //summary = null;
        //color = mCtx.getResources().getColor(R.color.favorites_primary);
        //mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_favorites,
        //    NavigationDrawerItemType.SINGLE, title, summary, R.drawable.ic_favorite_on, color));

        // Divider
        mNavigationDrawerItemList.add(new NavigationDrawerItem(0, NavigationDrawerItemType.DIVIDER,
                null, null, 0, 0));

        // Load Local Storage
        title = mCtx.getResources().getString(R.string.navigation_item_title_local);
        summary = StorageHelper.getLocalStoragePath(mCtx);
        color = mCtx.getResources().getColor(R.color.default_primary);
        mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_internal,
                itemType, title, summary, R.drawable.ic_source_internal, color));

        // Show/hide root
        if (showRoot) {
            title = mCtx.getResources().getString(R.string.navigation_item_title_root);
            summary = FileHelper.ROOT_DIRECTORY;
            color = mCtx.getResources().getColor(R.color.root_primary);
            mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_root_d,
                    itemType, title, summary, R.drawable.ic_source_root_d, color));
        }

        loadExternalStorageItems(itemType);

        // Grab storageapi providers insertion spot in list.
        mLastRoot = mNavigationDrawerItemList.size();

        loadSecureStorage();

        // Divider
        mNavigationDrawerItemList.add(new NavigationDrawerItem(0, NavigationDrawerItemType.DIVIDER,
                null, null, 0, 0));

        // Load manage storage and settings
        title = mCtx.getResources().getString(R.string.navigation_item_title_manage);
        summary = null;
        color = mCtx.getResources().getColor(R.color.misc_primary);
        mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_manage,
                NavigationDrawerItemType.SINGLE, title, summary, R.drawable.ic_storage_sources,
                color));
        title = mCtx.getResources().getString(R.string.navigation_item_title_settings);
        summary = null;
        color = mCtx.getResources().getColor(R.color.misc_primary);
        mNavigationDrawerItemList.add(new NavigationDrawerItem(R.id.navigation_item_settings,
                NavigationDrawerItemType.SINGLE, title, summary, R.drawable.ic_settings, color));

        // Notify dataset changed here because we aren't sure when/if storage providers will return.
        updateDataSet();

    }

    /**
     * Method that loads the secure digital card and usb storage menu items from the system.
     */
    private void loadExternalStorageItems(NavigationDrawerItemType itemType) {
        List<Bookmark> sdBookmarks = new ArrayList<Bookmark>();
        List<Bookmark> usbBookmarks = new ArrayList<Bookmark>();

        try {
            // Recovery sdcards and usb from storage manager
            StorageVolume[] volumes =
                    StorageHelper.getStorageVolumes(mCtx, true);
            for (StorageVolume volume: volumes) {
                if (volume != null) {
                    String mountedState = volume.getState();
                    String path = volume.getPath();
                    if (!Environment.MEDIA_MOUNTED.equalsIgnoreCase(mountedState) &&
                            !Environment.MEDIA_MOUNTED_READ_ONLY.equalsIgnoreCase(mountedState)) {
                        if (DEBUG) {
                            Log.w(TAG, "Ignoring '" + path + "' with state of '"
                                    + mountedState + "'");
                        }
                        continue;
                    }
                    if (!TextUtils.isEmpty(path)) {
                        String lowerPath = path.toLowerCase(Locale.ROOT);
                        String name = null;
                        Bookmark bookmark;
                        if (lowerPath.contains(STR_USB)) {
                            name = mCtx.getString(R.string.navigation_item_title_usb);
                            usbBookmarks.add(new Bookmark(USB, name, path));
                        } else {
                            name = mCtx.getString(R.string.navigation_item_title_sdcard);
                            sdBookmarks.add(new Bookmark(SDCARD, name, path));
                        }
                    }
                }
            }

            String localStorage = mCtx.getResources().getString(R.string.local_storage_path);

            // Load the bookmarks
            for (Bookmark b : sdBookmarks) {
                if (TextUtils.equals(b.getPath(), localStorage)) continue;
                int hash = b.hashCode();
                int color = mCtx.getResources().getColor(R.color.sdcard_primary);
                mNavigationDrawerItemList.add(new NavigationDrawerItem(hash, itemType, b.getName(),
                        b.getPath(), R.drawable.ic_source_sd_card, color));
                mStorageBookmarks.put(hash, b);
            }
            for (Bookmark b : usbBookmarks) {
                int hash = b.hashCode();
                int color = mCtx.getResources().getColor(R.color.usb_primary);
                mNavigationDrawerItemList.add(new NavigationDrawerItem(hash, itemType, b.getName(),
                        b.getPath(), R.drawable.ic_source_usb, color));
                mStorageBookmarks.put(hash, b);
            }
        }
        catch (Throwable ex) {
            Log.e(TAG, "Load filesystem bookmarks failed", ex); //$NON-NLS-1$
        }
    }

    /**
     * Method that loads the secure storage mount point.
     */
    private void loadSecureStorage() {
        List<MountPoint> mps = VirtualMountPointConsole.getVirtualMountPoints();
        for (MountPoint mp : mps) {
            BOOKMARK_TYPE type = null;
            String name = null;
            if (mp.isSecure()) {
                type = BOOKMARK_TYPE.SECURE;
                name = mCtx.getResources().getString(R.string.navigation_item_title_protected);
                Bookmark b = new Bookmark(type, name, mp.getMountPoint());

                int hash = b.hashCode();
                int color = mCtx.getResources().getColor(R.color.protected_primary);
                mNavigationDrawerItemList.add(new NavigationDrawerItem(hash,
                        NavigationDrawerItemType.SINGLE, b.getName(), b.getPath(),
                        R.drawable.ic_source_protected, color));
                mStorageBookmarks.put(hash, b);
                break;
            } else {
                continue;
            }
        }
    }

    private void updateDataSet() {
        for (NavigationDrawerItem item : mNavigationDrawerItemList) {
            if (mCurrentSelection == item.getId()) {
                NavigationDrawerItemType type = item.getType();
                if (type != NavigationDrawerItemType.DIVIDER &&
                        type != NavigationDrawerItemType.HEADER) {
                    item.setSelected(true);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void deselectAll() {
        if (mNavigationDrawerItemList != null && !mNavigationDrawerItemList.isEmpty()) {
            for (NavigationDrawerItem item : mNavigationDrawerItemList) {
                item.setSelected(false);
            }
        }
    }

    public void removeAllItemsFromDrawer() {
        // reset menu list
        mNavigationDrawerItemList.clear();

        // reset hashmaps
        mStorageBookmarks.clear();
    }

    public Bookmark getBookmarkFromMenuItem(int key) {
        return mStorageBookmarks.get(key);
    }

    public void setSelected(int id) {
        // Unset old selection
        deselectAll();

        mCurrentSelection = id;
        updateDataSet();
    }

    /*
     * Set navigation item closest related to path as selected
     * This is for use with paths, and thus only works with paths from navigation fragment
     */
    private void setSelected(String path) {
        deselectAll();
        String volumePath = StorageHelper.getStorageVolumeFromPath(path);

        NavigationDrawerItem selectedItem = null;
        for (NavigationDrawerItem item : mNavigationDrawerItemList) {
            if (!TextUtils.isEmpty(volumePath) &&
                    TextUtils.equals(item.getSummary(), volumePath)) {
                mCurrentSelection = item.getId();
                item.setSelected(true);
                selectedItem = item;
                break;
            }
        }

         if (selectedItem != null) {
             int selectedColor = selectedItem.getSelectedColor();
             mNavigationDrawerItemList.get(0).setSelectedColor(selectedColor);
         }

        mAdapter.notifyDataSetChanged();
    }

    public int getColorForPath(String path) {
        String volumePath = null;

        volumePath = StorageHelper.getStorageVolumeFromPath(path);

        for (NavigationDrawerItem item : mNavigationDrawerItemList) {
            if (!TextUtils.isEmpty(volumePath) &&
                    TextUtils.equals(item.getSummary(), volumePath)) {
                return item.getSelectedColor();
            }
        }

        return mCtx.getResources().getColor(R.color.default_primary);
    }

    @Override
    public void onDirectoryChanged(FileSystemObject item) {
        if (DEBUG) Log.d(TAG, "onDirectoryChanged::" + item.getFullPath());
        setSelected(item.getFullPath());
    }
}
