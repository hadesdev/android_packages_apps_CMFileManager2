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

package com.cyanogenmod.filemanager.mstaru;

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * Created by herriojr on 8/10/15.
 */
/* package */ interface MostFrequentlyUsedContract {
    String AUTHORITY = "com.cyanogenmod.filemanager.mfu";

    class Item implements BaseColumns {
        /* package */ static final String TABLE_NAME = "items";

        public static final String FOLDER = TABLE_NAME;

        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/" + AUTHORITY + "." + FOLDER;

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + FOLDER);

        /* package */ static final String _LAST_DEGRADE_TIMESTAMP = "_last_degrade";
        public static final String KEY = "key";
        public static final String COUNT = "count";
    }
}
