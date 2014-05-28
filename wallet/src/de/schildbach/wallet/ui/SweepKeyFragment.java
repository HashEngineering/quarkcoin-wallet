package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.*;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.actionbarsherlock.app.SherlockFragment;
import com.google.bitcoin.core.*;
import com.google.bitcoin.script.Script;
import de.schildbach.wallet.*;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.sweep.SweepHelper;
import de.schildbach.wallet.sweep.UnspentOutput;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import hashengineering.quarkcoin.wallet.R;

/**
 * @author Maximilian Keller
 */
public class SweepKeyFragment extends SherlockFragment {

    private AbstractBindServiceActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private BlockchainService service;

    private State state = State.INPUT;
    private Transaction sweepTransaction = null;
    private boolean isBound = false;

    private CurrencyCalculatorLink amountCalculatorLink;

    private TransactionsListAdapter sweepTransactionListAdapter;
    private ListView sweepTransactionView;
    private Button viewGo;
    private Button viewCancel;
    private TextView sweepErorr;

    private BalanceRequestTask task;
    private ArrayList<UnspentOutput> unspentOutputs = new ArrayList<UnspentOutput>();

    private ECKey key = null;
    private BigInteger balance = BigInteger.ZERO;
    private BigInteger unconfBalance = BigInteger.ZERO;

    //chain urls
    private List<String> blockchainUrls = Arrays.asList(
            "http://qrk.blockr.io/api/v1/address/unspent/%s",
            "http://qrk.blockr.io/api/v1/address/unspent/%s" //need an alternate
            //"https://chain.so/api/v2/lite/unspent/%s"
    );

    private static final Logger log = LoggerFactory.getLogger(SweepKeyFragment.class);

    private static final BigInteger KB_DIVISOR = BigInteger.valueOf(1000);

    private static final int ID_RATE_LOADER = 0;
    private enum State
    {
        INPUT, PREPARATION, SENDING, SENT, FAILED, NOTHING_TO_DO
    }

    @Override
    public void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.sweep_key_fragment, container);

        final CurrencyAmountView dogeAmountView = (CurrencyAmountView) view.findViewById(R.id.sweep_balance_doge);
        dogeAmountView.setCurrencySymbol(config.getBtcPrefix());
        dogeAmountView.setInputPrecision(config.getBtcMaxPrecision());
        dogeAmountView.setHintPrecision(config.getBtcPrecision());
        dogeAmountView.setShift(config.getBtcShift());

        final CurrencyAmountView fiatAmountView = (CurrencyAmountView) view.findViewById(R.id.sweep_balnce_fiat);
        fiatAmountView.setInputPrecision(Constants.LOCAL_PRECISION);
        fiatAmountView.setHintPrecision(Constants.LOCAL_PRECISION);
        fiatAmountView.setReportBTC(true);
        amountCalculatorLink = new CurrencyCalculatorLink(dogeAmountView, fiatAmountView);
        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());

        sweepTransactionView = (ListView) view.findViewById(R.id.sweep_key_sent_transaction);
        sweepTransactionListAdapter = new TransactionsListAdapter(activity, wallet, application.maxConnectedPeers(), false);
        sweepTransactionView.setAdapter(sweepTransactionListAdapter);

        viewGo = (Button) view.findViewById(R.id.send_coins_go);
        viewGo.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                isAmountValid();
                if (everythingValid())
                    handleGo();
            }
        });

        amountCalculatorLink.setNextFocusId(viewGo.getId());

        viewCancel = (Button) view.findViewById(R.id.send_coins_cancel);
        viewCancel.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                if (state == State.INPUT)
                    activity.setResult(Activity.RESULT_CANCELED);

                activity.finish();
            }
        });

        if (savedInstanceState != null)
        {
            restoreInstanceState(savedInstanceState);
        }
        else
        {
            final Intent intent = activity.getIntent();

            if (intent.hasExtra(SweepKeyActivity.INTENT_EXTRA_KEY))
                key = (ECKey)intent.getSerializableExtra(SweepKeyActivity.INTENT_EXTRA_KEY);
        }

        sweepErorr = (TextView) view.findViewById(R.id.sweep_error);

        return view;
    }

    @Override
    public void onAttach(final Activity activity)
    {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();
        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(activity));
    }

    @Override
    public void onDestroyView()
    {
        super.onDestroyView();
        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
    }

    @Override
    public void onResume()
    {
        super.onResume();
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        updateView();
        if (task == null) {
            task = new BalanceRequestTask();
            task.execute(key.toAddress(Constants.NETWORK_PARAMETERS).toString());
        }
    }

    @Override
    public void onPause()
    {
        loaderManager.destroyLoader(ID_RATE_LOADER);
        amountCalculatorLink.setListener(null);
        super.onPause();
    }

    @Override
    public void onDetach()
    {
        super.onDetach();
    }

    @Override
    public void onDestroy()
    {
        if (sweepTransaction != null)
            sweepTransaction.getConfidence().removeEventListener(sweepTransactionConfidenceListener);
        if (isBound)
            try {
                activity.unbindService(serviceConnection);
            } catch (Exception ignore){} // We probably already unbound earlier.
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        saveInstanceState(outState);
    }

    private void saveInstanceState(final Bundle outState)
    {
        outState.putSerializable("state", state);
        outState.putBoolean("isBound", isBound);
        if (key != null)
            outState.putSerializable("key", key);
    }

    private void restoreInstanceState(final Bundle savedInstanceState)
    {
        state = (State) savedInstanceState.getSerializable("state");
        isBound = savedInstanceState.getBoolean("isBound");
        if (savedInstanceState.containsKey("key"))
            key = (ECKey)savedInstanceState.getSerializable("key");
    }

    private final TransactionConfidence.Listener sweepTransactionConfidenceListener = new TransactionConfidence.Listener()
    {
        @Override
        public void onConfidenceChanged(final Transaction tx, final TransactionConfidence.Listener.ChangeReason reason)
        {
            activity.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    sweepTransactionListAdapter.notifyDataSetChanged();

                    final TransactionConfidence confidence = sweepTransaction.getConfidence();
                    final TransactionConfidence.ConfidenceType confidenceType = confidence.getConfidenceType();
                    final int numBroadcastPeers = confidence.numBroadcastPeers();

                    if (state == State.SENDING)
                    {
                        if (confidenceType == TransactionConfidence.ConfidenceType.DEAD)
                            state = State.FAILED;
                        else if (numBroadcastPeers > 1 || confidenceType == TransactionConfidence.ConfidenceType.BUILDING)
                            state = State.SENT;

                        updateView();
                    }

                    if (reason == ChangeReason.SEEN_PEERS && confidenceType == TransactionConfidence.ConfidenceType.PENDING)
                    {
                        // play sound effect
                        final int soundResId = getResources().getIdentifier("send_coins_broadcast_" + numBroadcastPeers, "raw",
                                activity.getPackageName());
                        if (soundResId > 0)
                            RingtoneManager.getRingtone(activity, Uri.parse("android.resource://" + activity.getPackageName() + "/" + soundResId))
                                    .play();
                    }
                }
            });
        }
    };

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
    {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
        {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
        {
            if (data != null && data.getCount() > 0)
            {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                if (state == State.INPUT)
                    amountCalculatorLink.setExchangeRate(exchangeRate);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader)
        {
        }
    };

    private boolean isAmountValid()
    {
        final BigInteger amount = amountCalculatorLink.getAmount();
        return amount != null && amount.signum() > 0;
    }

    private boolean everythingValid()
    {
        return state == State.INPUT && isAmountValid();
    }

    private void updateView()
    {
        amountCalculatorLink.setEnabled(false);

        if (sweepTransaction != null)
        {
            final int btcPrecision = config.getBtcPrecision();
            final int btcShift = config.getBtcShift();

            sweepTransactionView.setVisibility(View.VISIBLE);
            sweepTransactionListAdapter.setPrecision(btcPrecision, btcShift);
            sweepTransactionListAdapter.replace(sweepTransaction);
        }
        else
        {
            sweepTransactionView.setVisibility(View.GONE);
            sweepTransactionListAdapter.clear();
        }

        if (state == State.INPUT)
        {
            amountCalculatorLink.setBtcAmount(balance);
            viewCancel.setText(R.string.button_cancel);
            viewGo.setText(R.string.sweep_sweep);
        }
        else if (state == State.PREPARATION)
        {
            viewCancel.setText(R.string.button_cancel);
            viewGo.setText(R.string.send_coins_preparation_msg);
        }
        else if (state == State.SENDING)
        {
            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_sending_msg);
        }
        else if (state == State.SENT)
        {
            amountCalculatorLink.setBtcAmount(BigInteger.ZERO);
            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_sent_msg);
        }
        else if (state == State.FAILED)
        {
            if (unconfBalance.signum() == 1)
            {
                String error = activity.getString(R.string.sweep_unconfirmed, GenericUtils.formatValue(unconfBalance, config.getBtcPrecision(), config.getBtcShift()));
                sweepErorr.setText(error);
            }
            else
            {
                sweepErorr.setText(R.string.sweep_getbalance_failed);
            }

            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_failed_msg);
        } else if (state == State.NOTHING_TO_DO) {
            sweepErorr.setText(activity.getString(R.string.sweep_zero));
            viewCancel.setText(R.string.send_coins_fragment_button_back);
            viewGo.setText(R.string.send_coins_failed_msg);
        }

        viewCancel.setEnabled(state != State.PREPARATION);
        viewGo.setEnabled(everythingValid());
    }

    private void handleGo()
    {
        if (unconfBalance.signum() == 1) {
            state = State.FAILED;
            updateView();
        }

        sweepTransaction = new Transaction(Constants.NETWORK_PARAMETERS);
        ArrayList<Script> scripts = new ArrayList<Script>();
        BigInteger fee = BigInteger.ZERO;

        // initialize tx size counter
        BigInteger txSize = BigInteger.ZERO;

        // estimated size of signature
        // derived from https://github.com/langerhans/dogecoinj-new/blob/master/core/src/main/java/com/google/dogecoin/core/Wallet.java#L3650
        BigInteger sigSize = BigInteger.valueOf(key.getPubKeyHash().length + 75);

        for (UnspentOutput out : unspentOutputs)
        {
            Sha256Hash hash = new Sha256Hash(Hex.decode(out.getTxHash()));
            sweepTransaction.addInput(
                    new TransactionInput(
                            Constants.NETWORK_PARAMETERS,
                            sweepTransaction,
                            new byte[]{},
                            new TransactionOutPoint(
                                    Constants.NETWORK_PARAMETERS,
                                    out.getTxOutputN(),
                                    hash
                            )
                    )
            );

            byte[] scriptBytes = Hex.decode(out.getScript());
            scripts.add(new Script(scriptBytes));

            //add script size and signature size for every input to the tx size counter
            txSize = txSize.add(BigInteger.valueOf(scriptBytes.length)).add(sigSize);
        }

        Address myAddress = application.determineSelectedAddress();

        // initially, add the full balance to the tx
        sweepTransaction.addOutput(balance, myAddress);

        // add size of the serialized transaction to the tx
        // note that this figure by itself excludes scripts and signature data
        //TODO at this point we are still missing ~18 bytes per input, this should be fixed
        txSize = txSize.add(BigInteger.valueOf(sweepTransaction.bitcoinSerialize().length));

        // integer division by 1000, rounding up: (x+y-1)/y
        BigInteger sizeKb = txSize.add(KB_DIVISOR.subtract(BigInteger.ONE)).divide(KB_DIVISOR);

        // calculate fee by multiplying kilobyte size with default_min_tx_fee
        // note that result is in doge * 1e8
        fee = fee.add(sizeKb.multiply(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE));

        // Since we always just have one output for a sweep,
        // we set the value of vout[0] to balance - fee
        sweepTransaction.getOutput(0).setValue(balance.subtract(fee));

        state = State.PREPARATION;
        updateView();

        // Sign and send
        new SignAndSendTransactionTask().execute(scripts);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder)
        {
            service = ((BlockchainServiceImpl.LocalBinder) binder).getService();
            isBound = true;
            service.broadcastSweepTransaction(sweepTransaction);
            state = State.SENDING;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateView();
                }
            });
        }

        @Override
        public void onServiceDisconnected(final ComponentName name)
        {
            isBound = false;
            service = null;
        }
    };

    private class BalanceRequestTask extends AsyncTask<String, Integer, Integer>
    {
        ProgressDialog progress;
        @Override
        protected void onPreExecute () {
            progress = new ProgressDialog(activity);
            progress.setIndeterminate(true);
            progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progress.setTitle(R.string.sweep_getbalance_title);
            progress.setMessage(activity.getString(R.string.sweep_getbalance_text));
            progress.setCancelable(false);
            progress.show();

            unspentOutputs.clear();
        }

        @Override
        protected Integer doInBackground(String... address) {
            //randomize url order
            long seed = System.nanoTime();
            Collections.shuffle(blockchainUrls, new Random(seed));

            String url = blockchainUrls.get(0);
            Integer fetchResult = fetchUnspentOutputs(url, address);

            if (fetchResult == -1) {
                // try with alternate provider
                log.debug("Failed fetching unspent outputs from " + url + ", retrying...");
                fetchResult = fetchUnspentOutputs(blockchainUrls.get(1), address);
            }

            return fetchResult;
        }

        @Override
        protected void onPostExecute(Integer result) {
            try {
                progress.dismiss();
            } catch (Exception ignore){} // Happens during rotation

            switch (result)
            {
                case 1: // fetch successful and found unspent outs
                    calculateSpendableBalance();
                    if (unconfBalance.signum() == 1)
                        state = State.FAILED;
                    break;
                case 0: // fetch successful but no unspent outs
                    state = State.NOTHING_TO_DO;
                    break;
                case -1: // error occurred or we don't know what happened
                default:
                    state = State.FAILED;
                    break;
            }

            updateView();
        }

        private void calculateSpendableBalance() {
            balance = BigInteger.ZERO;
            unconfBalance = BigInteger.ZERO;

            for (UnspentOutput out : unspentOutputs)
            {
                //TODO: this gives a wrong result if there are coinbase outs on the wallet
                if (out.getConfirmations() >= 3)
                    balance = balance.add(out.getValue());
                else
                    unconfBalance = unconfBalance.add(out.getValue());
            }
        }

        private Integer fetchUnspentOutputs (String baseUrl, String... address) {
            HttpURLConnection connection = null;
            Reader reader = null;
            String urlString;

            // fail by default
            Integer result = -1;

            try
            {
                urlString = String.format(baseUrl, address);
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.connect();

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK)
                {
                    reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
                    final StringBuilder content = new StringBuilder();
                    Io.copy(reader, content);

                    // pass JSON content to the parser and accept it's
                    // result output as ours
                    result = parseUnspentJSON_blockr(content.toString());
                }
                else
                {
                    log.debug("http status " + responseCode + " when fetching unspent outputs");
                }
            }
            catch (final IOException x)
            {
                log.debug("problem reading unspent outputs", x);
            }
            finally
            {
                if (reader != null)
                {
                    try
                    {
                        reader.close();
                    }
                    catch (final IOException ignore){}
                }
                if (connection != null)
                    connection.disconnect();
            }

            return result;
        }

        private Integer parseUnspentJSON_blockr (String doc) {
            try
            {
                final JSONObject json = new JSONObject(doc);

                // json should validate itself, otherwise we do not trust it.
                String success = json.getString("status");

                if (!success.equals(success))
                    return -1;

                JSONObject dataJson = json.getJSONObject("data");
                JSONArray unspentJson = dataJson.getJSONArray("unspent");

                // if there are no unspent outputs, balance is 0
                // and we have nothing to sweep
                if(unspentJson.length() <= 0)
                    return 0;

                for (int i = 0 ; i < unspentJson.length(); i++)
                {
                    JSONObject output = unspentJson.getJSONObject(i);
                    UnspentOutput out = new UnspentOutput(
                            output.getString("tx"),
                            output.getInt("n"),
                            output.getString("script"),
                            BigInteger.valueOf((long)(Double.parseDouble(String.format("%.05f", output.getDouble("amount")).replace(",", "."))) *100000),
                            output.getInt("confirmations")
                    );
                    unspentOutputs.add(out);
                }
            } catch (JSONException e)
            {
                log.debug("Error while reading the JSON response");
                return -1;
            }

            return 1;
        }
    }
    private Integer parseUnspentJSON_abe (String doc) {
        try
        {
            final JSONObject json = new JSONObject(doc);

            // json should validate itself, otherwise we do not trust it.
            Integer success = json.getInt("success");
            if (success != 1)
                return -1;

            JSONArray unspentJson = json.getJSONArray("unspent_outputs");

            // if there are no unspent outputs, balance is 0
            // and we have nothing to sweep
            if(unspentJson.length() <= 0)
                return 0;

            for (int i = 0 ; i < unspentJson.length(); i++)
            {
                JSONObject output = unspentJson.getJSONObject(i);
                UnspentOutput out = new UnspentOutput(
                        output.getString("tx_hash"),
                        output.getInt("tx_output_n"),
                        output.getString("script"),
                        new BigInteger(output.getString("value")),
                        output.getInt("confirmations")
                );
                unspentOutputs.add(out);
            }
        } catch (JSONException e)
        {
            log.debug("Error while reading the JSON response");
            return -1;
        }

        return 1;
    }


    private class SignAndSendTransactionTask extends AsyncTask<ArrayList<Script>, Void, Void>
    {
        @Override
        protected void onPreExecute () {}

        @Override
        protected Void doInBackground(ArrayList<Script>... scripts) {
            SweepHelper.signTransactionInputs(sweepTransaction, Transaction.SigHash.ALL, key, scripts[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void x) {
            sweepTransaction.getConfidence().addEventListener(sweepTransactionConfidenceListener);

            // Now bind to the service so we can broadcast the transaction
            activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }
}