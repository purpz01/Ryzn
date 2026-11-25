package ryzn.macro.client.feature.rotation.impl.abcurves;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class AbcModels implements AutoCloseable {
  private static final String ROOT = "/assets/ryzn/abcurves/";
  private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
  private final Map<String, OrtSession> sessions = new LinkedHashMap<>();
  final double[][] basis = new double[64][21];
  final double[] carry = new double[64];

  AbcModels() throws IOException, OrtException {
    try (var input =
        new DataInputStream(
            new ByteArrayInputStream(
                asset(
                    "geometry.bin",
                    "24ea340febb7d00ea0e19ae61af25a5d9be2d372b126256d33a8ce649b20338e")))) {
      for (double[] row : basis) {
        for (int i = 0; i < row.length; i++) {
          row[i] = input.readDouble();
        }
      }
      for (int i = 0; i < carry.length; i++) {
        carry[i] = input.readDouble();
      }
    }
    try (var options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(1);
      options.setInterOpNumThreads(1);
      options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      options.addConfigEntry("session.intra_op.allow_spinning", "0");
      load(options, "motor", "116126ef8a6dc7aae027de596158d51e44a06fa30dcfd7058dfcde463106542c");
      load(options, "choice", "805f517a27219bc20fd0418be02cbf5316a601f040533056fe61304ef5e2ac74");
      load(options, "hazard", "ebd385e53ca15bb5ec5609018bdd93f2213875a41b3c78912986b00dfda12e53");
      load(options, "brake", "ec511415341b72284498ae0333cb12da6bf26e1f9c06562bc468766bfa636c24");
    } catch (IOException | OrtException | RuntimeException failure) {
      try {
        close();
      } catch (OrtException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private void load(OrtSession.SessionOptions options, String name, String digest)
      throws IOException, OrtException {
    sessions.put(name, environment.createSession(asset(name + ".onnx", digest), options));
  }

  private static byte[] asset(String name, String digest) throws IOException {
    byte[] bytes;
    try (var input = AbcModels.class.getResourceAsStream(ROOT + name)) {
      if (input == null) {
        throw new IOException("Missing ABCurves asset " + name);
      }
      bytes = input.readAllBytes();
    }
    try {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!digest.equals(actual)) {
        throw new IOException("ABCurves asset failed its integrity check: " + name);
      }
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    return bytes;
  }

  Object[] run(String model, Map<String, Object> inputs) {
    Map<String, OnnxTensor> tensors = new LinkedHashMap<>();
    try {
      for (var input : inputs.entrySet()) {
        tensors.put(input.getKey(), OnnxTensor.createTensor(environment, input.getValue()));
      }
      try (var result = sessions.get(model).run(tensors)) {
        var values = new Object[result.size()];
        for (int i = 0; i < values.length; i++) {
          values[i] = result.get(i).getValue();
        }
        return values;
      }
    } catch (OrtException exception) {
      throw new IllegalStateException("ABCurves inference failed for " + model, exception);
    } finally {
      tensors.values().forEach(OnnxTensor::close);
    }
  }

  @Override
  public void close() throws OrtException {
    OrtException failure = null;
    for (OrtSession session : sessions.values()) {
      try {
        session.close();
      } catch (OrtException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    sessions.clear();
    if (failure != null) {
      throw failure;
    }
  }
}
