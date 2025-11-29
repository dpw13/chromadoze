package net.pmarks.chromadoze;

import android.os.Parcel;
import android.os.Parcelable;

// SpectrumData is a Phonon translated into "machine readable" form.
//
// In other words, the values here are suitable for generating noise,
// and not for storage or rendering UI elements.

public class SpectrumData implements Parcelable {
    public static final Parcelable.Creator<SpectrumData> CREATOR
            = new Parcelable.Creator<SpectrumData>() {
        @Override
        public SpectrumData createFromParcel(Parcel in) {
            return new SpectrumData(in);
        }

        @Override
        public SpectrumData[] newArray(int size) {
            return new SpectrumData[size];
        }
    };

    private static final float MIN_FREQ = 100;
    private static final float MAX_FREQ = 20000;
    public static final int BAND_COUNT = 32;

    // The frequency of the edges between each bar.
    private static final int[] EDGE_FREQS = calculateEdgeFreqs();

    private final float[] mData;

    private static int[] calculateEdgeFreqs() {
        int[] edgeFreqs = new int[BAND_COUNT + 1];
        float range = MAX_FREQ / MIN_FREQ;
        for (int i = 0; i <= BAND_COUNT; i++) {
            edgeFreqs[i] = (int) (MIN_FREQ * Math.pow(range, (float) i / BAND_COUNT));
        }
        return edgeFreqs;
    }

    public SpectrumData(float[] donateBars) {
        if (donateBars.length != BAND_COUNT) {
            throw new RuntimeException("Incorrect number of bands");
        }
        mData = donateBars;
        for (int i = 0; i < BAND_COUNT; i++) {
            /* Input donateBars values are a linear scale in [0.0, 1.0].
             * Audio samples are 16 bits per channel or about 48 dB dynamic
             * range. Scale the input to [-50 dB, 0.0 dB] or [10^-5, 1.0] on
             * a log scale. As a special case, if the input is 0.0, completely
             * silence that frequency band.
             */
            if (mData[i] < 0.000001f) {
                mData[i] = 0f;
                continue;
            }
            /* This calculation was previously 0.001 * Math.pow(1000f, mData[i])
             * which is equal to Math.pow(10.0, 3*(mData[i] - 1)). I've changed
             * the equation to extend the input scaling down to below a single
             * LSB of audio output to ensure an appropriate volume difference
             * between the lowest non-zero value of a bin and zero. I've also
             * refactored the equation to make the relationship to dB clearer.
             * The exp variable is in units of dB/10.
             */
            float exp = 5f*(mData[i] - 1f);
            mData[i] = (float) Math.pow(10.0, exp);
        }
    }

    private SpectrumData(Parcel in) {
        mData = new float[BAND_COUNT];
        in.readFloatArray(mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloatArray(mData);
    }

    /*
     * Copies the configured band values to the frequency bins in out, copying the logarithmically
     * spaced bands to the linearly spaced frequency bins in the outputs.
     */
    public void fill(float[] out, int sampleRate) {
        int maxFreq = sampleRate / 2;
        subFill(out, 0f, 0, EDGE_FREQS[0], maxFreq);
        for (int i = 0; i < BAND_COUNT; i++) {
            subFill(out, mData[i], EDGE_FREQS[i], EDGE_FREQS[i + 1], maxFreq);
        }
        subFill(out, 0f, EDGE_FREQS[BAND_COUNT], maxFreq, maxFreq);
    }

    public void fillComplex(float[] out, int sampleRate) {
        int maxFreq = sampleRate / 2;
        subFillComplex(out, 0f, 0, EDGE_FREQS[0], maxFreq);
        for (int i = 0; i < BAND_COUNT; i++) {
            subFillComplex(out, mData[i], EDGE_FREQS[i], EDGE_FREQS[i + 1], maxFreq);
        }
        subFillComplex(out, 0f, EDGE_FREQS[BAND_COUNT], maxFreq, maxFreq);
    }

    /*
     * Sets the magnitude of all frequencies within the requested range to the specified value.
     */
    private void subFill(float[] out, float setValue, int startFreq, int limitFreq, int maxFreq) {
        // This min() applies if the sample rate is below 40kHz.
        int limitIndex = Math.min(out.length, limitFreq * out.length / maxFreq);
        for (int i = startFreq * out.length / maxFreq; i < limitIndex; i++) {
            out[i] = setValue;
        }
    }

    private void subFillComplex(float[] out, float setValue, int startFreq, int limitFreq, int maxFreq) {
        // This min() applies if the sample rate is below 40kHz.
        int bins = out.length / 2;
        int limitIndex = Math.min(bins, limitFreq * bins / maxFreq);
        for (int i = startFreq * bins / maxFreq; i < limitIndex; i++) {
            // Only set the real part of the frequency bins
            out[2 * i] = setValue;
            out[2 * i + 1] = 0f;
        }
    }

    public boolean sameSpectrum(SpectrumData other) {
        if (other == null) {
            return false;
        }
        for (int i = 0; i < BAND_COUNT; i++) {
            if (mData[i] != other.mData[i]) {
                return false;
            }
        }
        return true;
    }
}
