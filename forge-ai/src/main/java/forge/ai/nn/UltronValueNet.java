package forge.ai.nn;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TICKET-V4-008 (Ultron v4 Phase 2, P2.3): plain-Java forward pass for the small MLP value net
 * trained by {@code tools/nn/train.py}. No ONNX, no JNI, no new dependency -- see
 * {@code ULTRON_V4_NEURAL_PLAN.md} sect. 4.3.
 *
 * <p><b>Not wired into any AI decision path.</b> This class only loads a model file and computes
 * a forward pass; nothing in {@code forge.ai} calls it yet (that is a later ticket, deliberately
 * out of scope while the corpus-generation run in flight when this class was written is running).
 *
 * <p><b>Schema enforcement is the whole point.</b> {@link #load(Path)} refuses to load a model
 * file whose {@code schemaHash}/{@code semanticVersion} header fields do not match {@link
 * UltronStateEncoder#SCHEMA_HASH}/{@link UltronStateEncoder#ENCODER_SEMANTIC_VERSION} at the time
 * this JVM is running. A model trained against an old encoder silently producing garbage against
 * a new one is exactly the failure mode {@code SCHEMA_HASH} exists to catch -- see the encoder's
 * own javadoc.
 *
 * <p><b>Architecture.</b> {@code input -> Linear(fc1) -> ReLU -> LayerNorm(ln1) -> Linear(fc2) ->
 * ReLU -> LayerNorm(ln2) -> Linear(value_head) -> softmax(4)}. This mirrors {@code
 * tools/nn/train.py}'s {@code build_model()} exactly, including PyTorch's default LayerNorm
 * epsilon (1e-5) and biased (population, divide-by-N) variance -- both are common parity-test
 * failure points and are called out explicitly here so a future change to one side is not made
 * without updating the other.
 *
 * <p><b>Binary format</b> (big-endian throughout, matching the {@code DataOutputStream} /
 * {@code UltronStateLogger} convention already established elsewhere in this package):
 * <pre>
 * header:
 *   magic             int32   == MAGIC (0x554E5332, "UNS2")
 *   formatVersion     int32   == FORMAT_VERSION (1)
 *   schemaHash        int64   UltronStateEncoder.SCHEMA_HASH at export time
 *   semanticVersion   int32   UltronStateEncoder.ENCODER_SEMANTIC_VERSION at export time
 *   inputDim          int32
 *   hidden1           int32
 *   hidden2           int32
 *   numValueSlots     int32   == NUM_VALUE_SLOTS (4)
 * body (all float32, row-major, matches PyTorch nn.Linear's (out_features, in_features) layout):
 *   fc1.weight   [hidden1, inputDim]
 *   fc1.bias     [hidden1]
 *   ln1.weight   [hidden1]
 *   ln1.bias     [hidden1]
 *   fc2.weight   [hidden2, hidden1]
 *   fc2.bias     [hidden2]
 *   ln2.weight   [hidden2]
 *   ln2.bias     [hidden2]
 *   valueHead.weight  [NUM_VALUE_SLOTS, hidden2]
 *   valueHead.bias    [NUM_VALUE_SLOTS]
 * </pre>
 */
public final class UltronValueNet {

    public static final int MAGIC = 0x554E5332;
    public static final int FORMAT_VERSION = 1;
    public static final int NUM_VALUE_SLOTS = 4;
    private static final float LAYER_NORM_EPS = 1e-5f;

    private final long schemaHash;
    private final int semanticVersion;
    private final int inputDim;
    private final int hidden1;
    private final int hidden2;

    private final float[][] fc1Weight; // [hidden1][inputDim]
    private final float[] fc1Bias;     // [hidden1]
    private final float[] ln1Weight;   // [hidden1]
    private final float[] ln1Bias;     // [hidden1]
    private final float[][] fc2Weight; // [hidden2][hidden1]
    private final float[] fc2Bias;     // [hidden2]
    private final float[] ln2Weight;   // [hidden2]
    private final float[] ln2Bias;     // [hidden2]
    private final float[][] valueHeadWeight; // [NUM_VALUE_SLOTS][hidden2]
    private final float[] valueHeadBias;     // [NUM_VALUE_SLOTS]

    private UltronValueNet(long schemaHash, int semanticVersion, int inputDim, int hidden1, int hidden2,
                            float[][] fc1Weight, float[] fc1Bias, float[] ln1Weight, float[] ln1Bias,
                            float[][] fc2Weight, float[] fc2Bias, float[] ln2Weight, float[] ln2Bias,
                            float[][] valueHeadWeight, float[] valueHeadBias) {
        this.schemaHash = schemaHash;
        this.semanticVersion = semanticVersion;
        this.inputDim = inputDim;
        this.hidden1 = hidden1;
        this.hidden2 = hidden2;
        this.fc1Weight = fc1Weight;
        this.fc1Bias = fc1Bias;
        this.ln1Weight = ln1Weight;
        this.ln1Bias = ln1Bias;
        this.fc2Weight = fc2Weight;
        this.fc2Bias = fc2Bias;
        this.ln2Weight = ln2Weight;
        this.ln2Bias = ln2Bias;
        this.valueHeadWeight = valueHeadWeight;
        this.valueHeadBias = valueHeadBias;
    }

    public long getSchemaHash() {
        return schemaHash;
    }

    public int getSemanticVersion() {
        return semanticVersion;
    }

    public int getInputDim() {
        return inputDim;
    }

    /** Loads a model file, enforcing schema/semantic-version match against the live encoder. */
    public static UltronValueNet load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    /** As {@link #load(Path)} but from an arbitrary stream (tests can pass a fixture). */
    public static UltronValueNet load(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException(String.format(
                    "UltronValueNet: bad magic 0x%08x (expected 0x%08x) -- corrupt file or wrong format",
                    magic, MAGIC));
        }
        int formatVersion = in.readInt();
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("UltronValueNet: unsupported model format version " + formatVersion
                    + " (this loader supports " + FORMAT_VERSION + ")");
        }
        long schemaHash = in.readLong();
        int semanticVersion = in.readInt();

        // REFUSE TO LOAD on any mismatch -- this is the entire reason SCHEMA_HASH and
        // ENCODER_SEMANTIC_VERSION exist. A stale model loading silently against a changed
        // encoder is the exact failure mode being guarded (see class javadoc + UltronStateEncoder).
        if (schemaHash != UltronStateEncoder.SCHEMA_HASH) {
            throw new IOException(String.format(
                    "UltronValueNet: schema hash mismatch -- model was trained against 0x%016x but "
                            + "the running UltronStateEncoder is 0x%016x. Refusing to load a model "
                            + "trained against a different encoder schema.",
                    schemaHash, UltronStateEncoder.SCHEMA_HASH));
        }
        if (semanticVersion != UltronStateEncoder.ENCODER_SEMANTIC_VERSION) {
            throw new IOException(String.format(
                    "UltronValueNet: encoder semantic version mismatch -- model was trained against "
                            + "version %d but the running UltronStateEncoder is version %d. Refusing "
                            + "to load a model trained against different feature semantics.",
                    semanticVersion, UltronStateEncoder.ENCODER_SEMANTIC_VERSION));
        }

        int inputDim = in.readInt();
        int hidden1 = in.readInt();
        int hidden2 = in.readInt();
        int numValueSlots = in.readInt();
        if (numValueSlots != NUM_VALUE_SLOTS) {
            throw new IOException("UltronValueNet: expected " + NUM_VALUE_SLOTS + " value slots, "
                    + "file declares " + numValueSlots);
        }

        float[][] fc1Weight = readMatrix(in, hidden1, inputDim);
        float[] fc1Bias = readVector(in, hidden1);
        float[] ln1Weight = readVector(in, hidden1);
        float[] ln1Bias = readVector(in, hidden1);
        float[][] fc2Weight = readMatrix(in, hidden2, hidden1);
        float[] fc2Bias = readVector(in, hidden2);
        float[] ln2Weight = readVector(in, hidden2);
        float[] ln2Bias = readVector(in, hidden2);
        float[][] valueHeadWeight = readMatrix(in, NUM_VALUE_SLOTS, hidden2);
        float[] valueHeadBias = readVector(in, NUM_VALUE_SLOTS);

        return new UltronValueNet(schemaHash, semanticVersion, inputDim, hidden1, hidden2,
                fc1Weight, fc1Bias, ln1Weight, ln1Bias, fc2Weight, fc2Bias, ln2Weight, ln2Bias,
                valueHeadWeight, valueHeadBias);
    }

    private static float[] readVector(DataInputStream in, int n) throws IOException {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = in.readFloat();
        }
        return v;
    }

    private static float[][] readMatrix(DataInputStream in, int rows, int cols) throws IOException {
        float[][] m = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                m[r][c] = in.readFloat();
            }
        }
        return m;
    }

    /**
     * Forward pass. {@code input} must have length {@link #getInputDim()} (i.e.
     * {@code UltronStateEncoder.VECTOR_LENGTH} for a matching-schema model). Returns a
     * length-{@value #NUM_VALUE_SLOTS} softmax probability distribution over (self, opp1, opp2,
     * opp3) in the same self-relative order the encoder used to build {@code input}.
     */
    public float[] forward(float[] input) {
        if (input.length != inputDim) {
            throw new IllegalArgumentException("UltronValueNet.forward: expected input length "
                    + inputDim + ", got " + input.length);
        }
        float[] h1 = linear(input, fc1Weight, fc1Bias);
        relu(h1);
        layerNorm(h1, ln1Weight, ln1Bias);

        float[] h2 = linear(h1, fc2Weight, fc2Bias);
        relu(h2);
        layerNorm(h2, ln2Weight, ln2Bias);

        float[] logits = linear(h2, valueHeadWeight, valueHeadBias);
        return softmax(logits);
    }

    // -----------------------------------------------------------------------
    // Primitives -- plain float32 math throughout (no double accumulation),
    // matching PyTorch's default float32 forward pass.
    // -----------------------------------------------------------------------

    private static float[] linear(float[] x, float[][] weight, float[] bias) {
        int outDim = weight.length;
        float[] out = new float[outDim];
        for (int o = 0; o < outDim; o++) {
            float[] row = weight[o];
            float sum = 0f;
            for (int i = 0; i < row.length; i++) {
                sum += row[i] * x[i];
            }
            out[o] = sum + bias[o];
        }
        return out;
    }

    private static void relu(float[] x) {
        for (int i = 0; i < x.length; i++) {
            if (x[i] < 0f) {
                x[i] = 0f;
            }
        }
    }

    /** In-place LayerNorm over the whole vector (PyTorch nn.LayerNorm(dim) semantics: normalize
     *  over the last dimension, biased/population variance, then affine weight*x + bias). */
    private static void layerNorm(float[] x, float[] weight, float[] bias) {
        int n = x.length;
        float mean = 0f;
        for (float v : x) {
            mean += v;
        }
        mean /= n;
        float var = 0f;
        for (float v : x) {
            float d = v - mean;
            var += d * d;
        }
        var /= n; // biased (population) variance -- matches PyTorch LayerNorm, NOT Bessel-corrected
        float denom = (float) Math.sqrt(var + LAYER_NORM_EPS);
        for (int i = 0; i < n; i++) {
            x[i] = (x[i] - mean) / denom * weight[i] + bias[i];
        }
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            if (v > max) {
                max = v;
            }
        }
        float[] exps = new float[logits.length];
        float sum = 0f;
        for (int i = 0; i < logits.length; i++) {
            exps[i] = (float) Math.exp(logits[i] - max);
            sum += exps[i];
        }
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = exps[i] / sum;
        }
        return out;
    }
}
