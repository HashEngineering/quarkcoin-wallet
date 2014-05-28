package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import hashengineering.quarkcoin.wallet.R;

import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.widget.VideoView;

public class SplashVideo extends Activity implements OnCompletionListener, MediaPlayer.OnPreparedListener
{

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        //if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD)
        {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        setContentView(R.layout.video_splash);
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        Display display = getWindowManager().getDefaultDisplay();
        int height = display.getHeight();
        int width = display.getWidth();
        int r = display.getRotation();

        int videofile = 0;
        int land = R.raw.all_2_landscape;
        int port = R.raw.all_2_portrait;
        if(height > width)
        {
            videofile = (r == 1 || r == 3) ? R.raw.all_2_landscape : R.raw.all_2_portrait;
        }
        else
        {
            videofile = (r == 0 || r == 2) ? R.raw.all_2_portrait : R.raw.all_2_landscape;
        }





        VideoView video = (VideoView) findViewById(R.id.videoView);
        video.setVideoPath("android.resource://" + getPackageName() + "/"
                 + videofile);

        video.setMinimumWidth(width);
        video.setMinimumHeight(height);
        video.setBackgroundColor(Color.BLACK);
        video.start();
        video.setOnCompletionListener(this);
        video.setOnPreparedListener(this);
    }

    @Override
    public void onCompletion(MediaPlayer mp)
    {
        Intent intent = new Intent(this, WalletActivity.class);
        //intent.putExtra("originalOrientation", orientationAtStart+100);
        startActivity(intent);
        finish();
    }
    public void onPrepared(MediaPlayer mp)
    {
        VideoView video = (VideoView) findViewById(R.id.videoView);
        video.setBackgroundColor(Color.TRANSPARENT);
    }
}