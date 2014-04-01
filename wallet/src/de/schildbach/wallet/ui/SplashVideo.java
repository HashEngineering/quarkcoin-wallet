package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import de.schildbach.wallet.WalletApplication;
import hashengineering.quarkcoin.wallet.R;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Bundle;
import android.widget.VideoView;

public class SplashVideo extends Activity implements OnCompletionListener
{
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.video_splash);
        VideoView video = (VideoView) findViewById(R.id.videoView);
        video.setVideoPath("android.resource://" + getPackageName() + "/"
                 + R.raw.android5);
        video.start();
        video.setOnCompletionListener(this);
    }

    @Override
    public void onCompletion(MediaPlayer mp)
    {
        Intent intent = new Intent(this, WalletActivity.class);
        startActivity(intent);
        finish();
    }
}