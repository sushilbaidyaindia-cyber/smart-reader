package javax.sound.sampled;

/**
 * Android has no javax.sound. The pure-Java MP3 encoder (jump3r) only needs this small value class
 * to be told "16-bit PCM, N channels, sample rate", so a faithful copy of the JDK class is provided here.
 */
public class AudioFormat {

    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");
        public static final Encoding ULAW = new Encoding("ULAW");
        public static final Encoding ALAW = new Encoding("ALAW");

        private final String name;

        public Encoding(String name) { this.name = name; }

        @Override public final boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Encoding)) return false;
            return name.equals(((Encoding) obj).name);
        }

        @Override public final int hashCode() { return name.hashCode(); }

        @Override public final String toString() { return name; }
    }

    protected Encoding encoding;
    protected float sampleRate;
    protected int sampleSizeInBits;
    protected int channels;
    protected int frameSize;
    protected float frameRate;
    protected boolean bigEndian;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
    }

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this((signed ? Encoding.PCM_SIGNED : Encoding.PCM_UNSIGNED), sampleRate, sampleSizeInBits, channels,
                (channels == -1 || sampleSizeInBits == -1) ? -1 : ((sampleSizeInBits + 7) / 8) * channels,
                sampleRate, bigEndian);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }

    public boolean matches(AudioFormat format) {
        return format.getEncoding().equals(getEncoding())
                && (format.getChannels() == -1 || format.getChannels() == getChannels())
                && (format.getSampleRate() == -1f || format.getSampleRate() == getSampleRate())
                && (format.getSampleSizeInBits() == -1 || format.getSampleSizeInBits() == getSampleSizeInBits())
                && (format.getFrameRate() == -1f || format.getFrameRate() == getFrameRate())
                && (format.getFrameSize() == -1 || format.getFrameSize() == getFrameSize())
                && (getSampleSizeInBits() <= 8 || format.isBigEndian() == isBigEndian());
    }

    @Override
    public String toString() {
        return getEncoding() + " " + getSampleRate() + " Hz, " + getSampleSizeInBits() + " bit, "
                + getChannels() + " channels, " + getFrameSize() + " bytes/frame, "
                + (isBigEndian() ? "big-endian" : "little-endian");
    }
}
