package ryzn.macro.client.feature.rotation.impl.abcurves;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class AbcRandomTest {
  @Test
  void matchesUpstreamMixedDraws() throws Exception {
    try (var reader =
        new InputStreamReader(
            Objects.requireNonNull(
                getClass().getResourceAsStream("/abcurves/continuous-reference.json")),
            StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(reader).getAsJsonObject();
      var expected = fixture.getAsJsonObject("rng");
      var rng = new AbcRandom(fixture.get("seed").getAsLong());
      for (var value : expected.getAsJsonArray("uniforms")) {
        assertEquals(value.getAsFloat(), rng.uniform());
      }
      for (var value : expected.getAsJsonArray("exponentials")) {
        assertEquals(value.getAsFloat(), rng.exponential());
      }
      rng.discardCategorical(16);
      for (var value : expected.getAsJsonArray("afterDiscard")) {
        assertEquals(value.getAsFloat(), rng.uniform());
      }
    }
  }
}
