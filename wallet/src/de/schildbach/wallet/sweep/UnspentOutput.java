package de.schildbach.wallet.sweep;

import android.os.Parcel;
import android.os.Parcelable;

import java.math.BigInteger;

public class UnspentOutput implements Parcelable
{
    private String txHash;
    private Integer txOutputN;
    private String script;
    private BigInteger value;
    private Integer confirmations;

    public UnspentOutput(String txHash, Integer txOutputN, String script, BigInteger value, Integer confirmations) {
        this.txHash = txHash;
        this.txOutputN = txOutputN;
        this.script = script;
        this.value = value;
        this.confirmations = confirmations;
    }

    private UnspentOutput(Parcel in) {
        this.txHash = in.readString();
        this.txOutputN = in.readInt();
        this.script = in.readString();
        this.value = new BigInteger(in.readString());
        this.confirmations = in.readInt();
    }

    public String getTxHash() {
        return txHash;
    }

    public Integer getTxOutputN() {
        return txOutputN;
    }

    public String getScript() {
        return script;
    }

    public BigInteger getValue() {
        return value;
    }

    public Integer getConfirmations() {
        return confirmations;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(txHash);
        dest.writeInt(txOutputN);
        dest.writeString(script);
        dest.writeString(value.toString());
        dest.writeInt(confirmations);
    }

    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public UnspentOutput createFromParcel(Parcel in) {
            return new UnspentOutput(in);
        }

        public UnspentOutput[] newArray(int size) {
            return new UnspentOutput[size];
        }
    };
}