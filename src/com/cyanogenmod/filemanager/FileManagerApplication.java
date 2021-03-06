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

package com.cyanogenmod.filemanager;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.cyanogenmod.filemanager.console.Console;
import com.cyanogenmod.filemanager.console.ConsoleAllocException;
import com.cyanogenmod.filemanager.console.ConsoleBuilder;
import com.cyanogenmod.filemanager.console.ConsoleHolder;
import com.cyanogenmod.filemanager.console.VirtualMountPointConsole;
import com.cyanogenmod.filemanager.console.shell.PrivilegedConsole;
import com.cyanogenmod.filemanager.mstaru.IMostStarUsedFilesManager;
import com.cyanogenmod.filemanager.preferences.AccessMode;
import com.cyanogenmod.filemanager.preferences.FileManagerSettings;
import com.cyanogenmod.filemanager.preferences.ObjectStringIdentifier;
import com.cyanogenmod.filemanager.preferences.Preferences;
import com.cyanogenmod.filemanager.providers.secure.SecureCacheCleanupService;
import com.cyanogenmod.filemanager.service.MimeTypeIndexService;
import com.cyanogenmod.filemanager.util.AIDHelper;
import com.cyanogenmod.filemanager.util.AndroidHelper;
import com.cyanogenmod.filemanager.util.MimeTypeHelper;
import com.cyanogenmod.filemanager.util.StorageHelper;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A class that wraps the information of the application (constants,
 * identifiers, statics variables, ...).
 * @hide
 */
public final class FileManagerApplication extends Application {

    private static final String TAG = "FileManagerApplication"; //$NON-NLS-1$

    private static boolean DEBUG = false;
    private static Properties sSystemProperties;

    private static Map<String, Boolean> sOptionalCommandsMap;

    /**
     * A constant that contains the main process name.
     * @hide
     */
    public static final String MAIN_PROCESS = "com.cyanogenmod.filemanager"; //$NON-NLS-1$

    //Static resources
    private static FileManagerApplication sApp;
    private static ConsoleHolder sBackgroundConsole;

    private static boolean sIsDebuggable = false;
    private static boolean sHasShellCommands = false;
    private static boolean sIsDeviceRooted = false;

    private final BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                if (intent.getAction().compareTo(
                        FileManagerSettings.INTENT_SETTING_CHANGED) == 0) {

                    // The settings has changed
                    String key =
                            intent.getStringExtra(FileManagerSettings.EXTRA_SETTING_CHANGED_KEY);
                    if (key != null &&
                        key.compareTo(FileManagerSettings.SETTINGS_SHOW_TRACES.getId()) == 0) {

                        // The debug traces setting has changed. Notify to consoles
                        Console c = null;
                        try {
                            c = getBackgroundConsole();
                        } catch (Exception e) {/**NON BLOCK**/}
                        if (c != null) {
                            c.reloadTrace();
                        }
                        try {
                            c = ConsoleBuilder.getConsole(context, false);
                            if (c != null) {
                                c.reloadTrace();
                            }
                        } catch (Throwable _throw) {/**NON BLOCK**/}
                    }
                }
            }
        }
    };

    private IMostStarUsedFilesManager mMStarUManager;


    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(TAG, "FileManagerApplication.onCreate"); //$NON-NLS-1$
        }
        init();
        register();

        // Kick off usage by mime type indexing for external storage; most likely use case for
        // file manager
        File externalStorage = Environment.getExternalStorageDirectory();
        MimeTypeIndexService.indexFileRoot(this, externalStorage.getAbsolutePath());
        MimeTypeIndexService.indexFileRoot(this, Environment.getRootDirectory().getAbsolutePath());
        StorageVolume[] storageVolumes = StorageHelper.getStorageVolumes(this, true);
        for (StorageVolume storageVolume : storageVolumes) {
            MimeTypeIndexService.indexFileRoot(this, storageVolume.getPath());
        }


        // Schedule in case not scheduled (i.e. never booted with this app on device
        SecureCacheCleanupService.scheduleCleanup(getApplicationContext());

        mMStarUManager = IMostStarUsedFilesManager.Factory.newInstance(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onTerminate() {
        if (DEBUG) {
            Log.d(TAG, "onTerminate"); //$NON-NLS-1$
        }
        try {
            unregisterReceiver(this.mNotificationReceiver);
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        try {
            destroyBackgroundConsole();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        try {
            ConsoleBuilder.destroyConsole();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
        super.onTerminate();
    }

    /**
     * Method that register the application context.
     */
    private void register() {
        // Register the notify broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(FileManagerSettings.INTENT_SETTING_CHANGED);
        registerReceiver(this.mNotificationReceiver, filter);
    }

    /**
     * Method that initializes the application.
     */
    private void init() {
        //Save the static application reference
        sApp = this;

        // Read the system properties
        sSystemProperties = new Properties();
        readSystemProperties();

        // Check if the application is debuggable
        sIsDebuggable = (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));

        // Check if the device has shell commands and if is rooted
        sHasShellCommands = areShellCommandsPresent();
        sIsDeviceRooted = isRootPresent();

        // Check optional commands
        loadOptionalCommands();

        //Sets the default preferences if no value is set yet
        Preferences.loadDefaults();

        // Read AIDs
        AIDHelper.getAIDs(getApplicationContext(), true);

        //Create a console for background tasks. Register the virtual console prior to
        // the real console so mount point can be listed properly
        VirtualMountPointConsole.registerVirtualConsoles(getApplicationContext());
        allocBackgroundConsole(getApplicationContext());

        //Force the load of mime types
        try {
            MimeTypeHelper.loadMimeTypes(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Mime-types failed.", e); //$NON-NLS-1$
        }
    }

    /**
     * Method that returns the singleton reference of the application.
     *
     * @return Application The application singleton reference
     * @hide
     */
    public static FileManagerApplication getInstance() {
        return sApp;
    }

    /**
     * Method that returns if the application is debuggable
     *
     * @return boolean If the application is debuggable
     */
    public static boolean isDebuggable() {
        return sIsDebuggable;
    }

    /**
     * Method that returns if the device is rooted
     *
     * @return boolean If the device is rooted
     */
    public static boolean isDeviceRooted() {
        return sIsDeviceRooted;
    }

    /**
     * Method that returns if the device has all the required shell commands
     *
     * @return boolean If the device has all the required shell commands
     */
    public static boolean hasShellCommands() {
        return sHasShellCommands;
    }

    /**
     * Method that returns if a command is present in the system
     *
     * @param commandId The command key
     * @return boolean If the command is present
     */
    public static boolean hasOptionalCommand(String commandId) {
        return sOptionalCommandsMap.containsKey(commandId) && sOptionalCommandsMap.get(commandId);
    }

    /**
     * Method that returns a system property value
     *
     * @param property The system property key
     * @return String The system property value
     */
    public static String getSystemProperty(String property) {
        return sSystemProperties.getProperty(property);
    }

    /**
     * Method that returns the application background console.
     *
     * @return Console The background console
     */
    public static Console getBackgroundConsole() {
        if (sBackgroundConsole == null ||
            sBackgroundConsole.getConsole() == null ||
            !sBackgroundConsole.getConsole().isActive()) {

            allocBackgroundConsole(getInstance().getApplicationContext());
        }
        return sBackgroundConsole.getConsole();
    }

    /**
     * Method that destroy the background console
     */
    public static void destroyBackgroundConsole() {
        try {
            sBackgroundConsole.dispose();
        } catch (Throwable ex) {
            /**NON BLOCK**/
        }
    }

    /**
     * Method that allocate a new background console
     *
     * @param ctx The current context
     */
    private static synchronized void allocBackgroundConsole(Context ctx) {
        try {
            // Dispose the current console
            if (sBackgroundConsole != null) {
                sBackgroundConsole.dispose();
                sBackgroundConsole = null;
            }

            //Create a console for background tasks
            if (ConsoleBuilder.isPrivileged()) {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createPrivilegedConsole(ctx));
            } else {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createNonPrivilegedConsole(ctx));
            }
        } catch (Exception e) {
            Log.e(TAG,
                    "Background console creation failed. " +  //$NON-NLS-1$
                    "This probably will cause a force close.", e); //$NON-NLS-1$
        }
    }

    /**
     * Method that changes the background console to a privileged console
     *
     * @throws ConsoleAllocException If the console can't be allocated
     */
    public static void changeBackgroundConsoleToPriviligedConsole()
            throws ConsoleAllocException {
        if (sBackgroundConsole == null ||
              !(sBackgroundConsole.getConsole() instanceof PrivilegedConsole)) {
            try {
                if (sBackgroundConsole != null) {
                    sBackgroundConsole.dispose();
                }
            } catch (Throwable ex) {/**NON BLOCK**/}

            // Change the privileged console
            try {
                sBackgroundConsole =
                        new ConsoleHolder(
                                ConsoleBuilder.createPrivilegedConsole(
                                        getInstance().getApplicationContext()));
            } catch (Exception e) {
                try {
                    if (sBackgroundConsole != null) {
                        sBackgroundConsole.dispose();
                    }
                } catch (Throwable ex) {/**NON BLOCK**/}
                sBackgroundConsole = null;
                throw new ConsoleAllocException(
                        "Failed to alloc background console", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Method that returns the access mode of the application
     *
     * @return boolean If the access mode of the application
     */
    public static AccessMode getAccessMode() {
        if (!sHasShellCommands) {
            return AccessMode.SAFE;
        }
        String defaultValue =
                ((ObjectStringIdentifier)FileManagerSettings.
                            SETTINGS_ACCESS_MODE.getDefaultValue()).getId();
        String id = FileManagerSettings.SETTINGS_ACCESS_MODE.getId();
        return AccessMode.fromId(Preferences.getSharedPreferences().getString(id, defaultValue));
    }

    public static boolean isRestrictSecondaryUsersAccess(Context context) {
        String value = Preferences.getWorldReadableProperties(
                context, FileManagerSettings.SETTINGS_RESTRICT_SECONDARY_USERS_ACCESS.getId());
        if (value == null) {
            value = String.valueOf(FileManagerSettings.SETTINGS_RESTRICT_SECONDARY_USERS_ACCESS.
                    getDefaultValue());
        }
        return Boolean.parseBoolean(value);
    }

    public static boolean checkRestrictSecondaryUsersAccess(Context context, boolean isChroot) {
        if (!AndroidHelper.isSecondaryUser(context)) {
            return true;
        }
        boolean needChroot = !isChroot && isRestrictSecondaryUsersAccess(context);
        if (!needChroot) {
            return true;
        }

        try {
            Preferences.savePreference(
                    FileManagerSettings.SETTINGS_ACCESS_MODE, AccessMode.SAFE, true);
        } catch (Throwable ex) {
            Log.w(TAG, "can't save console preference", ex); //$NON-NLS-1$
        }
        ConsoleBuilder.changeToNonPrivilegedConsole(context);

        // Notify the change
        Intent intent = new Intent(FileManagerSettings.INTENT_SETTING_CHANGED);
        intent.putExtra(FileManagerSettings.EXTRA_SETTING_CHANGED_KEY,
                FileManagerSettings.SETTINGS_ACCESS_MODE.getId());
        context.sendBroadcast(intent);
        return false;
    }

    /**
     * Method that reads the system properties
     */
    private static void readSystemProperties() {
        try {
            String propsFile =
                    getInstance().getApplicationContext().getString(R.string.system_props_file);
            Properties props = new Properties();
            props.load(new FileInputStream(new File(propsFile)));
            sSystemProperties = props;
        } catch (Throwable e) {
            Log.e(TAG,
                    "Failed to read system properties.", e); //$NON-NLS-1$
        }
    }

    /**
     * Method that check if all shell commands are present in the device
     *
     * @return boolean Check if the device has all of the shell commands
     */
    private boolean areShellCommandsPresent() {
        try {
            String shellCommands = getString(R.string.shell_required_commands);
            String[] commands = shellCommands.split(","); //$NON-NLS-1$
            int cc = commands.length;
            if (cc == 0) {
                //???
                Log.w(TAG, "No shell commands."); //$NON-NLS-1$
                return false;
            }
            for (String command : commands) {
                String c = command.trim();
                if (c.length() == 0) continue;
                File cmd = new File(c);
                if (!cmd.exists() || !cmd.isFile()) {
                    Log.w(TAG,
                            String.format(
                                    "Command %s not found. Exists: %s; IsFile: %s.", //$NON-NLS-1$
                                    c,
                                    String.valueOf(cmd.exists()),
                                    String.valueOf(cmd.isFile())));
                    return false;
                }
            }
            // All commands are present
            return true;
        } catch (Exception e) {
            Log.e(TAG,
                    "Failed to read shell commands.", e); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Method that check if root command are present in the device
     *
     * @return boolean True if root command is present
     */
    private boolean isRootPresent() {
        try {
            String rootCommand = getString(R.string.root_command);
            File cmd = new File(rootCommand);
            if (!cmd.exists() || !cmd.isFile()) {
                Log.w(TAG,
                        String.format(
                                "Command %s not found. Exists: %s; IsFile: %s.", //$NON-NLS-1$
                                rootCommand,
                                String.valueOf(cmd.exists()),
                                String.valueOf(cmd.isFile())));
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG,
                    "Failed to read root command.", e); //$NON-NLS-1$
        }
        return false;
    }

    @SuppressWarnings("boxing")
    private void loadOptionalCommands() {
        try {
            sOptionalCommandsMap = new HashMap<String, Boolean>();

            String shellCommands = getString(R.string.shell_optional_commands);
            String[] commands = shellCommands.split(","); //$NON-NLS-1$
            int cc = commands.length;
            if (cc == 0) {
                Log.w(TAG, "No optional commands."); //$NON-NLS-1$
                return;
            }
            for (String command : commands) {
                String c = command.trim();
                String key = c.substring(0, c.indexOf("=")).trim(); //$NON-NLS-1$
                c = c.substring(c.indexOf("=") + 1).trim(); //$NON-NLS-1$
                if (c.length() == 0) continue;
                File cmd = new File(c);
                Boolean found = Boolean.valueOf(cmd.exists() && cmd.isFile());
                sOptionalCommandsMap.put(key, found);
                if (DEBUG) {
                    Log.w(TAG,
                            String.format(
                                    "Optional command %s %s.", //$NON-NLS-1$
                                    c, found ? "found" : "not found")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        } catch (Exception e) {
            Log.e(TAG,
                    "Failed to read optional shell commands.", e); //$NON-NLS-1$
        }
    }

    public IMostStarUsedFilesManager getMStarUManager() {
        return mMStarUManager;
    }
}
