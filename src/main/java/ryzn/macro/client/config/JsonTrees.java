package ryzn.macro.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/** The only Gson parser/printer boundary. Ryzn code handles every domain key explicitly. */
public final class JsonTrees {

  private static final Gson printer = new GsonBuilder().setPrettyPrinting().create();

  private JsonTrees() {}

  public static JsonObject parseObject(Reader reader) throws IOException {
    try {
      JsonElement parsed = JsonParser.parseReader(reader);
      if (parsed == null || !parsed.isJsonObject()) {
        throw new IOException("JSON root must be an object");
      }
      return parsed.getAsJsonObject();
    } catch (RuntimeException malformed) {
      throw new IOException("Malformed JSON", malformed);
    }
  }

  public static JsonObject parseObject(String json) throws IOException {
    try {
      JsonElement parsed = JsonParser.parseString(json);
      if (parsed == null || !parsed.isJsonObject()) {
        throw new IOException("JSON root must be an object");
      }
      return parsed.getAsJsonObject();
    } catch (RuntimeException malformed) {
      throw new IOException("Malformed JSON", malformed);
    }
  }

  public static void write(JsonElement tree, Writer writer) throws IOException {
    printer.toJson(tree, writer);
  }

  public static String print(JsonElement tree) {
    return printer.toJson(tree);
  }
}
