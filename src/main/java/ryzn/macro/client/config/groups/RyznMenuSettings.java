package ryzn.macro.client.config.groups;

import static ryzn.macro.client.config.settings.ConfigSection.bool;
import static ryzn.macro.client.config.settings.ConfigSection.integer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import ryzn.macro.client.config.settings.ConfigSection;

public final class RyznMenuSettings implements ConfigSection {
  @Getter @Setter private boolean moveInMenu = true;
  @Getter @Setter private boolean levelExpanded;
  @Getter @Setter private boolean wheatExpanded;
  private List<String> sectionOrder = List.of();
  private List<String> openSections = List.of("level_7");
  private final Map<String, Position> positions = new HashMap<>();

  public record Position(int x, int y) {}

  public Position position(String section) {
    return positions.get(section);
  }

  public void setPosition(String section, Position position) {
    positions.put(section, position);
  }

  public void resetPositions() {
    positions.clear();
  }

  public List<String> sectionOrder() {
    return sectionOrder;
  }

  public void setSectionOrder(List<String> order) {
    sectionOrder = List.copyOf(order);
  }

  public List<String> openSections() {
    return openSections;
  }

  public void setOpenSections(List<String> sections) {
    openSections = List.copyOf(sections);
  }

  @Override
  public String id() {
    return "ryzn_menu";
  }

  @Override
  public void read(JsonObject object) {
    moveInMenu = bool(object, "move_in_menu", true);
    List<String> order = new ArrayList<>();
    if (object.get("section_order") instanceof JsonArray saved) {
      for (var element : saved) {
        if (element instanceof JsonPrimitive name && name.isString() && order.size() < 5) {
          order.add(name.getAsString());
        }
      }
    }
    setSectionOrder(order);
    List<String> sections = new ArrayList<>();
    if (object.get("open_sections") instanceof JsonArray saved) {
      for (var element : saved) {
        if (element instanceof JsonPrimitive name && name.isString()) {
          sections.add(name.getAsString());
        }
      }
    } else {
      sections.add("level_7");
    }
    setOpenSections(sections);
    levelExpanded = bool(object, "level_expanded", false);
    wheatExpanded = bool(object, "wheat_expanded", false);
    positions.clear();
    if (object.get("floating_positions") instanceof JsonObject floating) {
      floating
          .entrySet()
          .forEach(
              entry -> {
                if (entry.getValue() instanceof JsonObject point) {
                  positions.put(
                      entry.getKey(),
                      new Position(
                          integer(point, "x", 0, 0, 1000), integer(point, "y", 0, 0, 1000)));
                }
              });
    }
  }

  @Override
  public JsonObject write() {
    JsonObject object = new JsonObject();
    object.addProperty("move_in_menu", moveInMenu);
    JsonArray order = new JsonArray();
    sectionOrder.forEach(order::add);
    object.add("section_order", order);
    JsonArray sections = new JsonArray();
    openSections.forEach(sections::add);
    object.add("open_sections", sections);
    object.addProperty("level_expanded", levelExpanded);
    object.addProperty("wheat_expanded", wheatExpanded);
    JsonObject floating = new JsonObject();
    positions.forEach(
        (section, position) -> {
          var point = new JsonObject();
          point.addProperty("x", position.x());
          point.addProperty("y", position.y());
          floating.add(section, point);
        });
    object.add("floating_positions", floating);
    return object;
  }
}
