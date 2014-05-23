package de.schildbach.wallet.ui;

/**
 * Created by Eric on 5/7/14.
 */

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebSettings.PluginState;
import hashengineering.quarkcoin.wallet.R;
import de.schildbach.wallet.ui.WalletActivity;

public class FlashActivity extends Activity {

    private static int SPLASH_TIME_OUT = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // TODO Auto-generated method stub
        super.onCreate(savedInstanceState);
        setContentView(R.layout.flash_splash);

        //String localUrl = "file:///android_res/raw/splashpage.html";

        //String localUrl2 = "android.resource://" + getPackageName() + "/"
         //       + R.raw.splashpage;

        WebView wv=(WebView) findViewById(R.id.webview);
        wv.getSettings().setPluginState(PluginState.ON);
        //wv.loadUrl(localUrl);

        new Handler().postDelayed(new Runnable() {

            /*
             * Showing splash screen with a timer. This will be useful when you
             * want to show case your app logo / company
             */

            @Override
            public void run() {
                // This method will be executed once the timer is over
                // Start your app main activity
                Intent yes_krao = new Intent(FlashActivity.this, WalletActivity.class);
                startActivity(yes_krao);
                finish();
            }
        }, SPLASH_TIME_OUT);
    }
}
