package forge.ai.nn;

import forge.ai.AITest;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * TICKET-V4-008 (Ultron v4 Phase 2, P2.3): the parity test. Per {@code ULTRON_V4_NEURAL_PLAN.md}
 * sect. 6 P2.3, this is described as "the single most important test in the whole plan" --
 * {@code tools/nn/train.py} and {@link UltronValueNet} must agree to within 1e-5 on real logged
 * states, not synthetic vectors.
 *
 * <p><b>Fixture.</b> {@code tools/nn/train.py} writes three files into its run directory at the
 * end of every training run:
 * <ul>
 *   <li>{@code model.bin} -- the exported {@link UltronValueNet} artifact.</li>
 *   <li>{@code parity_vectors.bin} -- up to N real logged input vectors (drawn from the same
 *       training corpus, NOT synthetic), header {@code magic(int32) count(int32) dim(int32)}
 *       followed by {@code count * dim} big-endian float32s.</li>
 *   <li>{@code parity_python_probs.bin} -- PyTorch's forward-pass softmax output for each of
 *       those same vectors on the SAME model that was exported to {@code model.bin} (not a
 *       reimplementation -- the actual trained {@code nn.Module}), header
 *       {@code magic(int32) count(int32) numSlots(int32)} followed by
 *       {@code count * numSlots} big-endian float32s.</li>
 * </ul>
 *
 * <p>This test loads {@code model.bin} through the real {@link UltronValueNet#load(Path)} path
 * (the same loader production code would use, including the schema-hash enforcement check),
 * runs {@link UltronValueNet#forward(float[])} on every vector in {@code parity_vectors.bin}, and
 * asserts each output matches the corresponding row of {@code parity_python_probs.bin} to within
 * {@link #TOLERANCE} (1e-5) in every slot.
 *
 * <p><b>Locating the fixture.</b> Point the {@code ultron.parity.dir} system property (or
 * {@code ULTRON_PARITY_DIR} environment variable) at a {@code tools/nn/runs/<timestamp>/}
 * directory produced by a real {@code train.py} run. If neither is set, the test is SKIPPED
 * (not failed) with an explanatory message -- there is no synthetic fallback, per the plan's
 * explicit "not synthetic vectors" requirement; a skip here means the parity claim simply was not
 * exercised in that particular test invocation, which must not be conflated with a pass.
 *
 * <p><b>If this test fails</b>, the plan's own guidance (sect. 6 P2.3 commentary in the dispatch
 * brief) is: chase LayerNorm epsilon placement, weight-matrix row/column ordering, or
 * float32-vs-float64 accumulation differences first -- do not loosen {@link #TOLERANCE}.
 */
public class UltronValueNetParityTest extends AITest {

    private static final double TOLERANCE = 1e-5;
    private static final int PARITY_MAGIC = 0x55504152; // "UPAR", must match tools/nn/train.py

    private Path resolveParityDir() {
        String prop = System.getProperty("ultron.parity.dir");
        if (prop == null || prop.isEmpty()) {
            prop = System.getenv("ULTRON_PARITY_DIR");
        }
        if (prop == null || prop.isEmpty()) {
            throw new SkipException("UltronValueNetParityTest skipped: no fixture directory given. "
                    + "Run tools/nn/train.py to produce a runs/<timestamp>/ directory (it writes "
                    + "model.bin, parity_vectors.bin, parity_python_probs.bin), then re-run with "
                    + "-Dultron.parity.dir=<that directory> or ULTRON_PARITY_DIR=<that directory>.");
        }
        return Paths.get(prop);
    }

    @Test
    public void testJavaForwardPassMatchesPythonWithin1e5() throws IOException {
        // FModel/Localizer must be initialized before touching UltronStateEncoder (its static
        // SCHEMA_HASH computation reads PhaseType, which needs the Localizer set up) -- same
        // requirement as UltronStateEncoderTest/UltronStateLoggerTest, both of which extend
        // AITest for exactly this reason.
        initAndCreateGame();

        Path dir = resolveParityDir();
        Path modelPath = dir.resolve("model.bin");
        Path vectorsPath = dir.resolve("parity_vectors.bin");
        Path probsPath = dir.resolve("parity_python_probs.bin");
        Assert.assertTrue(Files.exists(modelPath), "missing " + modelPath);
        Assert.assertTrue(Files.exists(vectorsPath), "missing " + vectorsPath);
        Assert.assertTrue(Files.exists(probsPath), "missing " + probsPath);

        UltronValueNet net = UltronValueNet.load(modelPath);

        float[][] vectors = readFloatMatrix(vectorsPath);
        float[][] pythonProbs = readFloatMatrix(probsPath);

        Assert.assertEquals(vectors.length, pythonProbs.length,
                "vectors.bin and python_probs.bin disagree on record count");
        Assert.assertTrue(vectors.length >= 100,
                "parity fixture has only " + vectors.length + " real states; plan requires >=100");

        double maxAbsDeviation = 0.0;
        int worstIdx = -1;
        int worstSlot = -1;
        for (int i = 0; i < vectors.length; i++) {
            float[] javaProbs = net.forward(vectors[i]);
            Assert.assertEquals(javaProbs.length, pythonProbs[i].length);
            for (int slot = 0; slot < javaProbs.length; slot++) {
                double dev = Math.abs(javaProbs[slot] - pythonProbs[i][slot]);
                if (dev > maxAbsDeviation) {
                    maxAbsDeviation = dev;
                    worstIdx = i;
                    worstSlot = slot;
                }
            }
        }

        System.out.println("UltronValueNetParityTest: " + vectors.length + " real states, "
                + "max abs deviation = " + maxAbsDeviation
                + " at record " + worstIdx + " slot " + worstSlot
                + " (tolerance " + TOLERANCE + ")");

        Assert.assertTrue(maxAbsDeviation < TOLERANCE,
                "Java/Python forward-pass parity FAILED: max abs deviation " + maxAbsDeviation
                        + " >= tolerance " + TOLERANCE + " (record " + worstIdx + ", slot " + worstSlot + ")");
    }

    @Test
    public void testRefusesToLoadOnSchemaHashMismatch() throws IOException {
        initAndCreateGame();
        byte[] bogus = buildBogusModelHeader(UltronStateEncoder.SCHEMA_HASH + 1,
                UltronStateEncoder.ENCODER_SEMANTIC_VERSION);
        try {
            UltronValueNet.load(new java.io.ByteArrayInputStream(bogus));
            Assert.fail("expected an IOException for a schema hash mismatch");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("schema hash mismatch"),
                    "unexpected message: " + expected.getMessage());
        }
    }

    @Test
    public void testRefusesToLoadOnSemanticVersionMismatch() throws IOException {
        initAndCreateGame();
        byte[] bogus = buildBogusModelHeader(UltronStateEncoder.SCHEMA_HASH,
                UltronStateEncoder.ENCODER_SEMANTIC_VERSION + 1);
        try {
            UltronValueNet.load(new java.io.ByteArrayInputStream(bogus));
            Assert.fail("expected an IOException for a semantic version mismatch");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("semantic version mismatch"),
                    "unexpected message: " + expected.getMessage());
        }
    }

    /** Builds a syntactically-valid-but-tiny model header (magic/format ok, dims trivially small)
     *  with a deliberately wrong schema hash or semantic version, to exercise the refuse-to-load
     *  path. This is the one place in this file synthetic (non-real-game) bytes are appropriate --
     *  the "not synthetic vectors" rule is about the parity claim itself, not about unit-testing
     *  the loader's own guard clauses. */
    private static byte[] buildBogusModelHeader(long schemaHash, int semanticVersion) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        out.writeInt(UltronValueNet.MAGIC);
        out.writeInt(UltronValueNet.FORMAT_VERSION);
        out.writeLong(schemaHash);
        out.writeInt(semanticVersion);
        out.writeInt(1); // inputDim
        out.writeInt(1); // hidden1
        out.writeInt(1); // hidden2
        out.writeInt(UltronValueNet.NUM_VALUE_SLOTS);
        // No body bytes -- load() must throw before ever trying to read weights.
        return bos.toByteArray();
    }

    /** Reads a {@code magic(int32) count(int32) width(int32)} + {@code count*width} float32
     *  big-endian file (the format both parity_vectors.bin and parity_python_probs.bin share). */
    private static float[][] readFloatMatrix(Path path) throws IOException {
        try (InputStream rawIn = Files.newInputStream(path)) {
            DataInputStream in = new DataInputStream(rawIn);
            int magic = in.readInt();
            if (magic != PARITY_MAGIC) {
                throw new IOException(String.format(
                        "%s: bad magic 0x%08x (expected 0x%08x)", path, magic, PARITY_MAGIC));
            }
            int count = in.readInt();
            int width = in.readInt();
            float[][] out = new float[count][width];
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < width; j++) {
                    out[i][j] = in.readFloat();
                }
            }
            return out;
        }
    }
}
