package net.pmarks.chromadoze;

import android.os.Process;
import android.os.SystemClock;

import org.jtransforms.dct.FloatDCT_1D;
import org.jtransforms.fft.FloatFFT_1D;

class SampleGenerator {
    private final NoiseService mNoiseService;
    private final AudioParams mParams;
    private final SampleShuffler mSampleShuffler;
    private final Thread mWorkerThread;

    private final boolean mUseFft;

    // Communication variables; must be synchronized.
    private boolean mStopping;
    private SpectrumData mPendingSpectrum;

    // Variables accessed from the thread only.
    private int mLastWindowSize = -1;
    private FloatDCT_1D mDct;
    private FloatFFT_1D mFft;
    private final XORShiftRandom mRandom = new XORShiftRandom();  // Not thread safe.

    public SampleGenerator(NoiseService noiseService, AudioParams params,
                           SampleShuffler sampleShuffler) {
        mNoiseService = noiseService;
        mParams = params;
        mSampleShuffler = sampleShuffler;
        mUseFft = true;

        mWorkerThread = new Thread("SampleGeneratorThread") {
            @Override
            public void run() {
                try {
                    threadLoop();
                } catch (StopException e) {
                }
            }
        };
        mWorkerThread.start();
    }

    public void stopThread() {
        synchronized (this) {
            mStopping = true;
            notify();
        }
        try {
            mWorkerThread.join();
        } catch (InterruptedException e) {
        }
    }

    public synchronized void updateSpectrum(SpectrumData spectrum) {
        mPendingSpectrum = spectrum;
        notify();
    }

    private void threadLoop() throws StopException {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Chunk-making progress:
        final SampleGeneratorState state = new SampleGeneratorState();
        SpectrumData spectrum = null;
        long waitMs = -1;

        while (true) {
            // This does one of 3 things:
            // - Throw StopException if stopThread() was called.
            // - Check if a new spectrum is waiting.
            // - Block if there's no work to do.
            final SpectrumData newSpectrum = popPendingSpectrum(waitMs);
            if (newSpectrum != null && !newSpectrum.sameSpectrum(spectrum)) {
                spectrum = newSpectrum;
                state.reset();
                mNoiseService.updatePercentAsync(state.getPercent());
            } else if (waitMs == -1) {
                // Nothing changed.  Keep waiting.
                continue;
            }

            final long startMs = SystemClock.elapsedRealtime();

            // Generate the next chunk of sound.
            float[] noiseData;
            if (mUseFft) {
                noiseData = doIFFT(state.getChunkSize(), spectrum);
            } else {
                noiseData = doIDCT(state.getChunkSize(), spectrum);
            }
            if (mSampleShuffler.handleChunk(noiseData, state.getStage())) {
                // Not dropped.
                state.advance();
                mNoiseService.updatePercentAsync(state.getPercent());
            }

            // Avoid burning the CPU while the user is scrubbing.  For the
            // first couple large chunks, the next chunk should be ready
            // when this one is ~75% finished playing.
            final long sleepTargetMs = state.getSleepTargetMs(mParams.SAMPLE_RATE);
            final long elapsedMs = SystemClock.elapsedRealtime() - startMs;
            waitMs = sleepTargetMs - elapsedMs;
            if (waitMs < 0) waitMs = 0;
            if (waitMs > sleepTargetMs) waitMs = sleepTargetMs;

            if (state.done()) {
                // No chunks left; save RAM.
                mDct = null;
                mLastWindowSize = -1;
                waitMs = -1;
            }
        }
    }

    private synchronized SpectrumData popPendingSpectrum(long waitMs)
            throws StopException {
        if (waitMs != 0 && !mStopping && mPendingSpectrum == null) {
            // Wait once.  The retry loop is in the caller.
            try {
                if (waitMs < 0) {
                    wait(/*forever*/);
                } else {
                    wait(waitMs);
                }
            } catch (InterruptedException e) {
            }
        }
        if (mStopping) {
            throw new StopException();
        }
        try {
            return mPendingSpectrum;
        } finally {
            mPendingSpectrum = null;
        }
    }

    private float[] doIDCT(int xformSize, SpectrumData spectrum) {
        if (xformSize != mLastWindowSize) {
            mDct = new FloatDCT_1D(xformSize);
            mLastWindowSize = xformSize;
        }
        float[] dctData = new float[xformSize];

        spectrum.fill(dctData, mParams.SAMPLE_RATE);

        // Multiply by a block of white noise.
        for (int i = 0; i < xformSize; ) {
            long rand = mRandom.nextLong();
            for (int b = 0; b < 4; b++) {
                /* Magnitude is in the range [0.0, 1.0) */
                dctData[i++] *= (float)(rand & 0xFFFF) / (65536f);
                rand >>= 16;
            }
        }

        mDct.inverse(dctData, 0, true);
        return dctData;
    }

    private float[] doIFFT(int xformSize, SpectrumData spectrum) {
        if (xformSize != mLastWindowSize) {
            mFft = new FloatFFT_1D(xformSize);
            mLastWindowSize = xformSize;
        }
        /* Inputs are complex, but for a real signal the negative frequency
         * components are symmetric with the positive frequency components,
         * so we still only need N samples. */
        float[] fftData = new float[xformSize];

        spectrum.fillComplex(fftData, mParams.SAMPLE_RATE);

        // Multiply by a block of white noise.
        for (int i = 2; i < xformSize / 2; ) {
            long rand = mRandom.nextLong();
            for (int b = 0; b < 2; b++) {
                /* Scale requested magnitude by a random value and rotate by a random angle */
                float magnitude = fftData[2*i] * (rand & 0xFFFF) / (65536f);
                float angle = (float)Math.TAU * (rand & 0xFFFF) / (65536f);
                fftData[2*i] = (float)(magnitude * Math.cos(angle));
                fftData[2*i + 1] = (float)(magnitude * Math.sin(angle));
                i++;
                rand >>= 32;
            }
        }

        mFft.realInverse(fftData, 0, true);
        return fftData;
    }

    private static class StopException extends Exception {
    }
}
