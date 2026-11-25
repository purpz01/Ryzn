package ryzn.macro.client.feature.rotation.impl.abcurves;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class AbcParityTest {
  @Test
  void reproducesSyntheticUpstreamStreams() throws Exception {
    try (var models = new AbcModels();
        var reader =
            new InputStreamReader(
                Objects.requireNonNull(
                    getClass().getResourceAsStream("/abcurves/continuous-reference.json")),
                StandardCharsets.UTF_8)) {
      var reference = JsonParser.parseReader(reader).getAsJsonObject();
      for (var entry : reference.getAsJsonObject("streams").entrySet()) {
        var fixture = entry.getValue().getAsJsonObject();
        var stream = new AbcStream(new AbcPolicy(models, reference.get("seed").getAsLong()));
        for (var receipt : fixture.getAsJsonArray("receipts")) {
          var row = receipt.getAsJsonArray();
          var target = row.get(1).getAsJsonArray();
          stream.updateTarget(
              target.get(0).getAsDouble(), target.get(1).getAsDouble(), row.get(0).getAsLong());
        }
        var samples = fixture.getAsJsonArray("samples");
        var actual =
            stream.advance(samples.get(samples.size() - 1).getAsJsonArray().get(0).getAsLong());
        assertEquals(samples.size(), actual.size(), entry.getKey());
        assertEquals(fixture.get("decisions").getAsInt(), stream.decisions(), entry.getKey());
        for (int i = 0; i < samples.size(); i++) {
          var sample = samples.get(i).getAsJsonArray();
          var result = actual.get(i);
          assertEquals(sample.get(0).getAsLong(), result.timeMicros());
          assertPosition(sample.get(1).getAsDouble(), result.x(), entry.getKey() + " x " + i);
          assertPosition(sample.get(2).getAsDouble(), result.y(), entry.getKey() + " y " + i);
        }
      }
    }
  }

  private static void assertPosition(double expected, double actual, String message) {
    assertEquals(expected, actual, 0.001 + Math.abs(expected) * 0.00001, message);
  }
}
