/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;


import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.google.bitcoin.core.CoinDefinition;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider
{
	public static class ExchangeRate
	{
		public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, final String source)
		{
			this.currencyCode = currencyCode;
			this.rate = rate;
			this.source = source;
		}

		public final String currencyCode;
		public final BigInteger rate;
		public final String source;

		@Override
		public String toString()
		{

			return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue_BTC(rate, Constants.BTC_MAX_PRECISION, 0) + ']';

			//return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatDebugValue(rate) + ']';

		}
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE = "rate";
	private static final String KEY_SOURCE = "source";

	private Configuration config;
	private String userAgent;

	@CheckForNull
	private Map<String, ExchangeRate> exchangeRates = null;
	private long lastUpdated = 0;

	private static final URL BITCOINAVERAGE_URL;
	private static final String[] BITCOINAVERAGE_FIELDS = new String[] { "24h_avg", "last" };
	private static final String BITCOINAVERAGE_SOURCE = "BitcoinAverage.com";
	private static final URL BLOCKCHAININFO_URL;
	private static final String[] BLOCKCHAININFO_FIELDS = new String[] { "15m" };
	private static final String BLOCKCHAININFO_SOURCE = "blockchain.info";

	// https://bitmarket.eu/api/ticker

	static
	{
		try
		{
			BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/custom/abw");
			BLOCKCHAININFO_URL = new URL("https://blockchain.info/ticker");
		}
		catch (final MalformedURLException x)
		{
			throw new RuntimeException(x); // cannot happen
		}
	}

	private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

	@Override
	public boolean onCreate()
	{
		final Context context = getContext();

		this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));

		this.userAgent = WalletApplication.httpUserAgent(WalletApplication.packageInfoFromContext(context).versionName);

		final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
		if (cachedExchangeRate != null)
		{
			exchangeRates = new TreeMap<String, ExchangeRate>();
			exchangeRates.put(cachedExchangeRate.currencyCode, cachedExchangeRate);
		}

		return true;
	}

	public static Uri contentUri(@Nonnull final String packageName)
	{
		return Uri.parse("content://" + packageName + '.' + "exchange_rates");
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		if (Constants.BUG_OPENSSL_HEARTBLEED)
			return null;

		final long now = System.currentTimeMillis();

		if (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS)
		{
			Map<String, ExchangeRate> newExchangeRates = null;
			if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(BITCOINAVERAGE_URL, userAgent, BITCOINAVERAGE_SOURCE, BITCOINAVERAGE_FIELDS);
			if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(BLOCKCHAININFO_URL, userAgent, BLOCKCHAININFO_SOURCE, BLOCKCHAININFO_FIELDS);

			if (newExchangeRates != null)
			{
				exchangeRates = newExchangeRates;
				lastUpdated = now;

				final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
				if (exchangeRateToCache != null)
					config.setCachedExchangeRate(exchangeRateToCache);
			}
		}

		if (exchangeRates == null)
			return null;

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
			{
				final ExchangeRate rate = entry.getValue();
				cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final ExchangeRate rate = bestExchangeRate(selectionArgs[0]);

            if (rate != null)
			    cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);

		}

		return cursor;
	}

	private ExchangeRate bestExchangeRate(final String currencyCode)
	{
		ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
		if (rate != null)
			return rate;

		final String defaultCode = defaultCurrencyCode();
		rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

		if (rate != null)
			return rate;

		return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
	}

	private String defaultCurrencyCode()
	{
		try
		{
			return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
		}
		catch (final IllegalArgumentException x)
		{
			return null;
		}
	}

	public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
	{
		final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
		final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
		final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

		return new ExchangeRate(currencyCode, rate, source);
	}

	@Override
	public Uri insert(final Uri uri, final ContentValues values)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(final Uri uri, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getType(final Uri uri)
	{
		throw new UnsupportedOperationException();
	}


    private static Object getCoinValueBTC()
    {




        //final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        // Keep the LTC rate around for a bit
        Double btcRate = 0.0;
        String currencyCryptsy = CoinDefinition.cryptsyMarketCurrency;
        String urlCryptsy = "http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid="+ CoinDefinition.cryptsyMarketId;




        try {
            // final String currencyCode = currencies[i];
            final URL URLCryptsy = new URL(urlCryptsy);
            final HttpURLConnection connectionCryptsy = (HttpURLConnection)URLCryptsy.openConnection();
            connectionCryptsy.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connectionCryptsy.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connectionCryptsy.connect();

            final StringBuilder contentCryptsy = new StringBuilder();

            Reader reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(connectionCryptsy.getInputStream(), 1024));
                Io.copy(reader, contentCryptsy);
                final JSONObject head = new JSONObject(contentCryptsy.toString());
                JSONObject returnObject = head.getJSONObject("return");
                JSONObject markets = returnObject.getJSONObject("markets");
                JSONObject coinInfo = markets.getJSONObject(CoinDefinition.coinTicker);



                JSONArray recenttrades = coinInfo.getJSONArray("recenttrades");

                double btcTraded = 0.0;
                double coinTraded = 0.0;

                for(int i = 0; i < recenttrades.length(); ++i)
                {
                    JSONObject trade = (JSONObject)recenttrades.get(i);

                    btcTraded += trade.getDouble("total");
                    coinTraded += trade.getDouble("quantity");

                }

                Double averageTrade = btcTraded / coinTraded;



                //Double lastTrade = GLD.getDouble("lasttradeprice");



                //String euros = String.format("%.7f", averageTrade);
                // Fix things like 3,1250
                //euros = euros.replace(",", ".");
                //rates.put(currencyCryptsy, new ExchangeRate(currencyCryptsy, Utils.toNanoCoins(euros), URLCryptsy.getHost()));
                if(currencyCryptsy.equalsIgnoreCase("BTC")) btcRate = averageTrade;
                return btcRate;
            }
            finally
            {
                if (reader != null)
                    reader.close();
            }

        }
        catch (final IOException x)
        {
            x.printStackTrace();
        }
        catch (final JSONException x)
        {
            x.printStackTrace();
        }

        return null;
    }

    private static Object getCoinValueBTC_BTER()
    {
        //final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        // Keep the LTC rate around for a bit
        Double btcRate = 0.0;
        String currency = CoinDefinition.cryptsyMarketCurrency;
        String url = "http://data.bter.com/api/1/ticker/"+ CoinDefinition.coinTicker.toLowerCase() + "_" + CoinDefinition.cryptsyMarketCurrency.toLowerCase();





        try {
            // final String currencyCode = currencies[i];
            final URL URL_bter = new URL(url);
            final HttpURLConnection connection = (HttpURLConnection)URL_bter.openConnection();
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            connection.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());
                String result = head.getString("result");
                if(result.equals("true"))
                {

                    Double averageTrade = head.getDouble("avg");


                    if(currency.equalsIgnoreCase("BTC"))
                        btcRate = averageTrade;
                }
                return btcRate;
            }
            finally
            {
                if (reader != null)
                    reader.close();
            }

        }
        catch (final IOException x)
        {
            x.printStackTrace();
        }
        catch (final JSONException x)
        {
            x.printStackTrace();
        }

        return null;
    }


	private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String userAgent, final String source, final String... fields)
	{
		final long start = System.currentTimeMillis();

		HttpURLConnection connection = null;
		Reader reader = null;

		try
		{

            Double btcRate = 0.0;
            boolean cryptsyValue = true;
            Object result = getCoinValueBTC();

            if(result == null)
            {
                result = getCoinValueBTC_BTER();
                cryptsyValue = false;
                if(result == null)
                    return null;
            }

            btcRate = (Double)result;


			connection = (HttpURLConnection) url.openConnection();

			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.addRequestProperty("User-Agent", userAgent);
			connection.addRequestProperty("Accept-Encoding", "gzip");
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				final String contentEncoding = connection.getContentEncoding();

				InputStream is = new BufferedInputStream(connection.getInputStream(), 1024);
				if ("gzip".equalsIgnoreCase(contentEncoding))
					is = new GZIPInputStream(is);

				reader = new InputStreamReader(is, Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				final long length = Io.copy(reader, content);

				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

				final JSONObject head = new JSONObject(content.toString());
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					final String currencyCode = i.next();
					if (!"timestamp".equals(currencyCode))
					{
						final JSONObject o = head.getJSONObject(currencyCode);

						for (final String field : fields)
						{
							String rateStr = o.optString(field, null);

							if (rateStr != null)
							{
								try
								{
                                    double rateForBTC = Double.parseDouble(rateStr);

                                    rateStr = String.format("%.8f", rateForBTC * btcRate).replace(",", ".");

									final BigInteger rate = GenericUtils.toNanoCoins_BTC(rateStr, 0);

									if (rate.signum() > 0)
									{
										rates.put(currencyCode, new ExchangeRate(currencyCode, rate, source));
										break;
									}
								}
								catch (final ArithmeticException x)
								{
									log.warn("problem fetching {} exchange rate from {} ({}): {}", currencyCode, url, contentEncoding, x.getMessage());
								}

							}
						}
					}
				}

				log.info("fetched exchange rates from {} ({}), {} chars, took {} ms", url, contentEncoding, length, System.currentTimeMillis()
						- start);

                //Add Bitcoin information
                if(rates.size() == 0)
                {
                    int i = 0;
                    i++;
                }
                else
                {
                    rates.put(CoinDefinition.cryptsyMarketCurrency, new ExchangeRate(CoinDefinition.cryptsyMarketCurrency, GenericUtils.toNanoCoins_BTC(String.format("%.8f", btcRate).replace(",", "."), 0), cryptsyValue ? "pubapi.cryptsy.com" : "data.bter.com"));
                    rates.put("m" + CoinDefinition.cryptsyMarketCurrency, new ExchangeRate("m" + CoinDefinition.cryptsyMarketCurrency, GenericUtils.toNanoCoins_BTC(String.format("%.5f", btcRate*1000).replace(",", "."), 0), cryptsyValue ? "pubapi.cryptsy.com" : "data.bter.com"));
                }


                return rates;
			}
			else
			{
				log.warn("http status {} when fetching {}", responseCode, url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates from " + url, x);
		}
		finally
		{

			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;
	}
}
