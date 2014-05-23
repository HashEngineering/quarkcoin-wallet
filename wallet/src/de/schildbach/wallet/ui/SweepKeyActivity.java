package de.schildbach.wallet.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuItem;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import de.schildbach.wallet.PaymentIntent;
import hashengineering.quarkcoin.wallet.R;

import javax.annotation.Nonnull;

/**
 * @author Maximilian Keller
 */
public class SweepKeyActivity extends AbstractBindServiceActivity {

    public static final String INTENT_EXTRA_KEY = "sweep_key";

    public static void start(final Context context, @Nonnull ECKey key)
    {
        final Intent intent = new Intent(context, SweepKeyActivity.class);
        intent.putExtra(INTENT_EXTRA_KEY, key);
        context.startActivity(intent);
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sweep_key_content);

        getWalletApplication().startBlockchainService(false);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item)
    {
        switch (item.getItemId())
        {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}