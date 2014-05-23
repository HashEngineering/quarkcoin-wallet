package de.schildbach.wallet.sweep;

import com.google.bitcoin.core.*;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class SweepHelper {
    private static final Logger log = LoggerFactory.getLogger(SweepHelper.class);

    // Taken from https://github.com/ksedgwic/Wallet32/blob/master/src/com/bonsai/wallet32/WalletUtil.java
    // TODO This is originally taken from the bitcoinj/dogecoinj and should really be there instead!
    // Thanks to devrandom!
    public static void signTransactionInputs(Transaction tx,
                                             Transaction.SigHash hashType,
                                             ECKey key,
                                             List<Script> inputScripts) throws ScriptException {

        List<TransactionInput> inputs = tx.getInputs();
        List<TransactionOutput> outputs = tx.getOutputs();

        checkState(inputs.size() > 0);
        checkState(outputs.size() > 0);

        checkArgument(hashType == Transaction.SigHash.ALL, "Only SIGHASH_ALL is currently supported");

        // The transaction is signed with the input scripts empty
        // except for the input we are signing. In the case where
        // addInput has been used to set up a new transaction, they
        // are already all empty. The input being signed has to have
        // the connected OUTPUT program in it when the hash is
        // calculated!
        //
        // Note that each input may be claiming an output sent to a
        // different key. So we have to look at the outputs to figure
        // out which key to sign with.

        TransactionSignature[] signatures = new TransactionSignature[inputs.size()];
        ECKey[] signingKeys = new ECKey[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            // We don't have the connected output, we assume it was
            // signed already and move on
            if (input.getScriptBytes().length != 0)
                log.warn("Re-signing an already signed transaction! Be sure this is what you want.");

            // This assert should never fire. If it does, it means the wallet is inconsistent.
            checkNotNull(key, "Transaction exists in wallet that we cannot redeem: %s",
                    input.getOutpoint().getHash());

            // Keep the key around for the script creation step below.
            signingKeys[i] = key;

            // The anyoneCanPay feature isn't used at the moment.
            boolean anyoneCanPay = false;
            signatures[i] = tx.calculateSignature(i, key, inputScripts.get(i), hashType, anyoneCanPay);
        }

        // Now we have calculated each signature, go through and
        // create the scripts. Reminder: the script consists:
        //
        // 1) For pay-to-address outputs: a signature (over a hash of
        // the simplified transaction) and the complete public key
        // needed to sign for the connected output. The output script
        // checks the provided pubkey hashes to the address and then
        // checks the signature.
        //
        // 2) For pay-to-key outputs: just a signature.
        //
        for (int i = 0; i < inputs.size(); i++) {
            if (signatures[i] == null)
                continue;
            TransactionInput input = inputs.get(i);
            Script scriptPubKey = inputScripts.get(i);
            if (scriptPubKey.isSentToAddress()) {

                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i],
                        signingKeys[i]));
            } else if (scriptPubKey.isSentToRawPubKey()) {

                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i]));
            } else {
                // Should be unreachable - if we don't recognize
                // the type of script we're trying to sign for,
                // then we should have failed above when fetching
                // the key to sign with.
                throw new RuntimeException("Do not understand script type: " + scriptPubKey);
            }
        }

        // Every input is now complete.
    }
}
