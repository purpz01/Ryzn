package ryzn.macro.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ryzn.macro.client.config.ConfigManager;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.RotationSettings;
import ryzn.macro.client.config.groups.RyznMenuSettings.Position;
import ryzn.macro.client.config.groups.WalkingSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.Level7ActionEvent;
import ryzn.macro.client.debug.DebugControl;
import ryzn.macro.client.feature.macros.impl.foraging.AfkAutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;

public final class RyznModulesScreen extends Screen {
  private enum Tab {
    LEVEL_7("Macros"),
    ROTATIONS("Rotations"),
    PATHFINDER("Pathfinder"),
    HUD("HUD"),
    DEVELOPER("Developer");

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  private static boolean menuKeyDown;
  private static boolean openWalkingOnNextTick;
  private final Screen parent;
  private final EnumSet<Tab> openSections = EnumSet.noneOf(Tab.class);
  private List<Tab> sectionOrder = List.of();
  private int left;
  private int top;
  private int panelWidth;
  private int panelHeight;
  private static final int HEADER_HEIGHT = 27;
  private static final int SINGLE_ROW_MIN_WIDTH = 490;
  private static final int MARGIN = 10;
  private static final int COLUMN_GAP = 8;
  private static final int WALKING_CONTROLS_TOP = 104;
  private final EnumMap<Tab, SectionBounds> sections = new EnumMap<>(Tab.class);
  private final EnumMap<Tab, RyznMenuStyle.MenuButton> headerButtons = new EnumMap<>(Tab.class);
  private Tab pressedHeader;
  private int pressX;
  private int pressY;
  private int dragOffsetX;
  private int dragOffsetY;
  private int rotationScroll;
  private int walkingScroll;
  private Tab buildingSection;
  private final List<Tab> frontOrder = new ArrayList<>();
  private final EnumMap<Tab, List<AbstractWidget>> panelWidgets = new EnumMap<>(Tab.class);
  private int scroll;
  private int maxScroll;
  private final List<RyznMenuStyle.MenuButton> toolbarButtons = new ArrayList<>();
  private boolean draggingHeader;
  private String configStatus = "";
  private boolean confirmingReset;
  private boolean sprintKeyDown;
  private boolean sprintToggled;
  private boolean sprintToggleChanged;
  private RyznMenuStyle.MenuButton cancelReset;
  private RyznMenuStyle.MenuButton applyReset;

  private record SectionBounds(int x, int y, int width, int height) {
    boolean contains(double mx, double my) {
      return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    boolean containsHeader(double mx, double my) {
      return contains(mx, my) && my < y + HEADER_HEIGHT;
    }
  }

  private int contentWidth;
  private int contentX;
  private boolean wheatExpanded;
  private RyznMenuStyle.MenuButton levelToggle;
  private RyznMenuStyle.MenuButton freshStart;

  public RyznModulesScreen(Screen parent) {
    super(Component.literal("Ryzn Modules"));
    this.parent = parent;
    var client = Minecraft.getInstance();
    sprintKeyDown = RyznMenuMovement.down(client, client.options.keySprint);
    sprintToggled = client.options.toggleSprint().get() && client.options.keySprint.isDown();
    readExpansion();
  }

  public static void openWalkingOnNextTick() {
    openWalkingOnNextTick = true;
  }

  public static void tickMenuKey(Minecraft client) {
    if (openWalkingOnNextTick
        && client.player != null
        && client.gui.screen() == null
        && RyznBootstrap.isReady()) {
      openWalkingOnNextTick = false;
      var screen = new RyznModulesScreen(null);
      screen.openSections.clear();
      screen.openSections.add(Tab.PATHFINDER);
      client.gui.setScreen(screen);
    }
    boolean down = InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RSHIFT);
    boolean pressed = down && !menuKeyDown;
    menuKeyDown = down;
    if (!pressed || !RyznBootstrap.isReady()) return;
    if (client.gui.screen() instanceof RyznModulesScreen modules) {
      if (modules.confirmingReset) modules.confirmingReset = false;
      else modules.onClose();
    } else if (client.player != null && client.gui.screen() == null) {
      client.gui.setScreen(new RyznModulesScreen(null));
    }
  }

  @Override
  protected void init() {
    readSectionOrder();
    layoutSections();
    levelToggle = freshStart = null;
    panelWidgets.clear();
    if (frontOrder.isEmpty()) frontOrder.addAll(sectionOrder);

    headerButtons.clear();
    for (Tab page : sectionOrder) {
      useSection(page);
      SectionBounds bounds = sections.get(page);
      boolean open = openSections.contains(page);
      headerButtons.put(
          page,
          addWidget(
              new RyznMenuStyle.MenuButton(
                  font,
                  page.label,
                  bounds.x(),
                  bounds.y(),
                  bounds.width(),
                  HEADER_HEIGHT,
                  open,
                  RyznMenuStyle.ButtonKind.SECTION,
                  () -> toggleSection(page))));
      if (!open) continue;
      int first = children().size();
      switch (page) {
        case LEVEL_7 -> addMacroCatalog();
        case ROTATIONS -> addRotationControls();
        case PATHFINDER -> addWalkingControls();
        case HUD -> addHudControls();
        case DEVELOPER -> addDeveloperControls();
      }
      panelWidgets.put(
          page,
          children().subList(first, children().size()).stream()
              .filter(AbstractWidget.class::isInstance)
              .map(AbstractWidget.class::cast)
              .toList());
    }

    toolbarButtons.clear();
    int configRight = singleRowToolbar() ? width - 212 : width - MARGIN;
    int configY = singleRowToolbar() ? 13 : 36;
    boolean allOpen = openSections.size() == Tab.values().length;
    toolbarButton(
        allOpen ? "Collapse all" : "Expand all",
        width - 208,
        78,
        () -> {
          if (allOpen) {
            openSections.clear();
          } else {
            openSections.addAll(EnumSet.allOf(Tab.class));
          }
          saveExpansion();
          rebuildWidgets();
        });
    toolbarButton(
        "Reset layout",
        width - 126,
        86,
        () -> {
          var settings = RyznSettings.get();
          settings.ryznMenu().setSectionOrder(List.of());
          settings.ryznMenu().resetPositions();
          frontOrder.clear();
          settings.save();
          scroll = 0;
          rebuildWidgets();
        });
    toolbarButton("Save config", configRight - 182, configY, 86, this::saveGlobalConfig);
    toolbarButton("Reset config", configRight - 92, configY, 92, this::confirmResetGlobalConfig);
    toolbarButton("×", width - 33, 23, this::onClose);
    int dialogX = (width - resetDialogWidth()) / 2;
    int buttonY = height / 2 + 20;
    int buttonWidth = (resetDialogWidth() - 32) / 2;
    cancelReset =
        new RyznMenuStyle.MenuButton(
            font,
            "Cancel",
            dialogX + 12,
            buttonY,
            buttonWidth,
            20,
            false,
            () -> confirmingReset = false);
    applyReset =
        new RyznMenuStyle.MenuButton(
            font,
            "Reset config",
            dialogX + 20 + buttonWidth,
            buttonY,
            buttonWidth,
            20,
            true,
            this::resetGlobalConfig);
    tick();
  }

  private void toolbarButton(String label, int x, int w, Runnable action) {
    toolbarButton(label, x, singleRowToolbar() ? 13 : 10, w, action);
  }

  private void toolbarButton(String label, int x, int y, int w, Runnable action) {
    toolbarButtons.add(
        addWidget(new RyznMenuStyle.MenuButton(font, label, x, y, w, 20, false, action)));
  }

  private void saveGlobalConfig() {
    configStatus = ConfigManager.global().saveNow() ? "Config saved" : "Could not save config";
  }

  boolean sprintInput() {
    boolean down = RyznMenuMovement.down(minecraft, minecraft.options.keySprint);
    boolean pressed = down && !sprintKeyDown;
    sprintKeyDown = down;
    if (!minecraft.options.toggleSprint().get()) return down;
    if (pressed) {
      sprintToggled = !sprintToggled;
      sprintToggleChanged = true;
    }
    return sprintToggled;
  }

  boolean isConfirmingReset() {
    return confirmingReset;
  }

  private void confirmResetGlobalConfig() {
    setFocused(null);
    confirmingReset = true;
  }

  private int resetDialogWidth() {
    return Math.min(280, width - 2 * MARGIN);
  }

  private void resetGlobalConfig() {
    confirmingReset = false;
    RyznBootstrap.failsafeManager().stopAllMacros("config reset");
    if (ConfigManager.global().resetToDefaults()) {
      configStatus = "Defaults restored";
      frontOrder.clear();
      scroll = rotationScroll = walkingScroll = 0;
    } else {
      configStatus = "Could not reset config";
    }
    readExpansion();
    rebuildWidgets();
  }

  private void readExpansion() {
    var menu = RyznSettings.get().ryznMenu();
    openSections.clear();
    for (Tab page : Tab.values()) {
      if (menu.openSections().contains(page.id())) openSections.add(page);
    }
    wheatExpanded = menu.isWheatExpanded();
  }

  private void saveExpansion() {
    var settings = RyznSettings.get();
    settings.ryznMenu().setOpenSections(openSections.stream().map(Tab::id).toList());
    settings.ryznMenu().setWheatExpanded(wheatExpanded);
    settings.save();
  }

  private void readSectionOrder() {
    List<String> saved = RyznSettings.get().ryznMenu().sectionOrder();
    List<Tab> ordered = new ArrayList<>(Tab.values().length);
    for (String id : saved) {
      for (Tab page : Tab.values()) {
        if (page.id().equals(id) && !ordered.contains(page)) ordered.add(page);
      }
    }
    for (Tab page : Tab.values()) {
      if (!ordered.contains(page)) ordered.add(page);
    }
    sectionOrder = List.copyOf(ordered);
  }

  private boolean singleRowToolbar() {
    return width >= SINGLE_ROW_MIN_WIDTH;
  }

  private int toolbarBottom() {
    return singleRowToolbar() ? 40 : 61;
  }

  private int workspaceTop() {
    return toolbarBottom() + 4;
  }

  private void layoutSections() {
    int usableWidth = width - 2 * MARGIN;
    int columns = Math.clamp((usableWidth + COLUMN_GAP) / 198, 1, sectionOrder.size());
    int columnWidth = (usableWidth - (columns - 1) * COLUMN_GAP) / columns;
    int x = MARGIN;
    int y = workspaceTop();
    int rowHeight = 0;
    for (Tab page : sectionOrder) {
      int sectionWidth =
          page == Tab.LEVEL_7 ? Math.min(usableWidth, Math.round(columnWidth * 2.5f)) : columnWidth;
      if (x > MARGIN && x + sectionWidth > width - MARGIN) {
        x = MARGIN;
        y += rowHeight + COLUMN_GAP;
        rowHeight = 0;
      }
      int sectionHeight = sectionHeight(page, sectionWidth);
      sections.put(page, new SectionBounds(x, y, sectionWidth, sectionHeight));
      x += sectionWidth + COLUMN_GAP;
      rowHeight = Math.max(rowHeight, sectionHeight);
    }
    y += rowHeight + COLUMN_GAP;
    maxScroll = Math.max(0, y - COLUMN_GAP + MARGIN - height);
    scroll = Math.clamp(scroll, 0, maxScroll);
    sections.replaceAll(
        (page, bounds) -> {
          Position saved = RyznSettings.get().ryznMenu().position(page.id());
          if (saved == null) {
            return new SectionBounds(
                bounds.x(), bounds.y() - scroll, bounds.width(), bounds.height());
          }
          return new SectionBounds(
              MARGIN + Math.round(saved.x() * travelX(bounds) / 1000f),
              workspaceTop()
                  + Math.min(travelY(bounds), Math.round(saved.y() * headerTravelY() / 1000f))
                  - (page == Tab.LEVEL_7 && bounds.height() > height - workspaceTop() - MARGIN
                      ? scroll
                      : 0),
              bounds.width(),
              bounds.height());
        });
  }

  private int travelX(SectionBounds bounds) {
    return Math.max(0, width - 2 * MARGIN - bounds.width());
  }

  private int travelY(SectionBounds bounds) {
    return Math.max(0, height - workspaceTop() - MARGIN - bounds.height());
  }

  private int headerTravelY() {
    return Math.max(0, height - workspaceTop() - MARGIN - HEADER_HEIGHT);
  }

  private int routeHeight(int panelWidth) {
    var level = RyznSettings.get().level7();
    int checkpoints = AutoLvl7Macro.routeCheckpointOrder().length;
    int stages = AutoLvl7Macro.resumeStageLabels(level.selectedCheckpoint()).length;
    int rows = panelWidth >= 560 ? Math.max(checkpoints, stages) : checkpoints + stages + 2;
    return HEADER_HEIGHT + 47 + rows * 24;
  }

  private int sectionHeight(Tab page, int sectionWidth) {
    if (!openSections.contains(page)) return HEADER_HEIGHT;
    return switch (page) {
      case LEVEL_7 -> routeHeight(sectionWidth);
      case ROTATIONS, PATHFINDER -> Math.min(278, Math.max(117, height - workspaceTop() - MARGIN));
      case DEVELOPER -> wheatExpanded ? 335 : 241;
      case HUD -> 100;
    };
  }

  private void useSection(Tab page) {
    SectionBounds bounds = sections.get(page);
    buildingSection = page;
    left = bounds.x();
    top = bounds.y() + HEADER_HEIGHT;
    panelWidth = bounds.width();
    panelHeight = bounds.height();
    contentX = left + 9;
    contentWidth = panelWidth - 18;
  }

  private void toggleSection(Tab page) {
    if (!openSections.add(page)) {
      openSections.remove(page);
    }
    saveExpansion();
    rebuildWidgets();
  }

  private void addMacroCatalog() {
    var level = RyznSettings.get().level7();
    boolean wide = panelWidth >= 560;
    int checkpointWidth = wide ? (contentWidth - 16) * 2 / 5 : contentWidth;
    int stageX = wide ? contentX + checkpointWidth + 16 : contentX;
    int stageWidth = contentX + contentWidth - stageX;
    int[] order = AutoLvl7Macro.routeCheckpointOrder();
    String[] checkpoints = AutoLvl7Macro.resumeCheckpointLabels();
    String[] stages = AutoLvl7Macro.resumeStageLabels(level.selectedCheckpoint());
    int checkpointY = top + 38;
    int stageY = wide ? checkpointY : checkpointY + (order.length + 2) * 24;

    int actionWidth = (contentWidth - 8) / 2;
    levelToggle =
        button(
            levelLabel(),
            contentX,
            top + 2,
            actionWidth,
            true,
            () ->
                runLevelAction(
                    level.isActive()
                        ? Level7ActionEvent.Action.STOP
                        : Level7ActionEvent.Action.START));
    freshStart =
        button(
            "Start from beginning",
            contentX + actionWidth + 8,
            top + 2,
            contentWidth - actionWidth - 8,
            false,
            () -> runLevelAction(Level7ActionEvent.Action.START_FRESH));

    for (int i = 0; i < order.length; i++) {
      int checkpoint = order[i];
      routeChoice(
          (i + 1) + ". " + checkpoints[checkpoint - 1],
          contentX,
          checkpointY + i * 24,
          checkpointWidth,
          checkpoint == level.selectedCheckpoint(),
          () -> {
            if (checkpoint == level.selectedCheckpoint()) return;
            level.selectCheckpoint(checkpoint);
            rebuildWidgets();
          });
    }
    for (int i = 0; i < stages.length; i++) {
      int stage = i + 1;
      routeChoice(
          stage + ". " + stages[i],
          stageX,
          stageY + i * 24,
          stageWidth,
          stage == level.selectedStage(),
          () -> {
            level.selectStage(stage);
            rebuildWidgets();
          });
    }
  }

  private void routeChoice(
      String label, int x, int y, int width, boolean selected, Runnable action) {
    button(label, x, y, width, selected, RyznMenuStyle.ButtonKind.LIST_ROW, action)
        .setTooltip(Tooltip.create(Component.literal(label)));
  }

  private void addWheatControls(int y) {
    WheatBreakerMacro wheat = RyznSettings.get().wheat();
    addRenderableWidget(
        new RyznMenuStyle.MenuButton(
            font,
            "Wheat Breaker",
            contentX,
            y,
            contentWidth,
            34,
            wheatExpanded,
            RyznMenuStyle.ButtonKind.CARD,
            () -> {
              wheatExpanded = !wheatExpanded;
              saveExpansion();
              rebuildWidgets();
            }));
    if (!wheatExpanded) return;

    sliderAt(
        "Sweep speed",
        -1,
        y + 39,
        20,
        1,
        5,
        "",
        wheat::getSweepLookSpeed,
        wheat::setSweepLookSpeed);
    sliderAt(
        "ABCurves tracking",
        -1,
        y + 62,
        20,
        WheatBreakerMacro.MIN_SWEEP_TRACKING_PERCENT,
        WheatBreakerMacro.MAX_SWEEP_TRACKING_PERCENT,
        "%",
        wheat::getSweepTrackingPercent,
        wheat::setSweepTrackingPercent);
    sliderAt(
        "Sweep width",
        -1,
        y + 85,
        20,
        60,
        150,
        "°",
        () -> Math.min(150, wheat.getSweepAmount()),
        wheat::setSweepAmount);
    sliderAt(
        "Crop aim pitch offset",
        -1,
        y + 108,
        20,
        -WheatBreakerMacro.MAX_SWEEP_PITCH_OFFSET,
        WheatBreakerMacro.MAX_SWEEP_PITCH_OFFSET,
        "°",
        wheat::getSweepPitchOffset,
        wheat::setSweepPitchOffset);
  }

  private void runLevelAction(Level7ActionEvent.Action action) {
    if (action != Level7ActionEvent.Action.STOP) {
      if (minecraft.player == null) return;
      minecraft.gui.setScreen(null);
    }
    RyznBootstrap.eventBus().post(new Level7ActionEvent(action));
  }

  private String levelLabel() {
    var level7 = RyznSettings.get().level7();
    if (level7.isActive()) return "Stop Auto Level 7";
    return "Start selected stage";
  }

  private static final int ROTATION_STEP = 24;

  private int rotationLimit() {
    var settings = RyznSettings.get().rotations();
    int rows = settings.isAbcEnabled() ? (settings.isRandomSeed() ? 9 : 10) : 2;
    return Math.max(
        0, rows * ROTATION_STEP + HEADER_HEIGHT + 12 - sections.get(Tab.ROTATIONS).height());
  }

  private void addRotationControls() {
    rotationScroll = Math.clamp(rotationScroll, 0, rotationLimit());
    var settings = RyznSettings.get().rotations();
    addRenderableWidget(
        new RyznMenuStyle.MenuButton(
            font,
            settings.isAbcEnabled() ? "Rotation mode · ABCurves" : "Rotation mode · Direct",
            contentX,
            settingsY(8),
            contentWidth,
            settingsRowHeight(),
            settings.isAbcEnabled(),
            () -> {
              settings.setAbcEnabled(!settings.isAbcEnabled());
              RyznSettings.get().save();
              rebuildWidgets();
            }));
    if (!settings.isAbcEnabled()) {
      return;
    }
    int y = 56;
    toggle("Random seed per turn session", -1, y, settings::isRandomSeed, settings::setRandomSeed);
    y += ROTATION_STEP;
    if (!settings.isRandomSeed()) {
      slider(
          "Fixed planner seed",
          -1,
          y,
          RotationSettings.MAX_SEED,
          "",
          settings::getAbcSeed,
          settings::setAbcSeed);
      y += ROTATION_STEP;
    }
    slider(
        "Yaw speed cap (0 = off)",
        -1,
        y,
        RotationSettings.MAX_RATE,
        "°/s",
        settings::getYawCap,
        settings::setYawCap);
    slider(
        "Pitch speed cap (0 = off)",
        -1,
        y + 24,
        RotationSettings.MAX_RATE,
        "°/s",
        settings::getPitchCap,
        settings::setPitchCap);
    slider(
        "Extra smoothing",
        -1,
        y + 48,
        RotationSettings.MAX_DAMPING_MS,
        " ms",
        settings::getDampingMillis,
        settings::setDampingMillis);
    slider(
        "Small-correction deadzone",
        -1,
        y + 72,
        RotationSettings.MAX_DEADZONE_TENTHS,
        " × 0.1°",
        settings::getDeadzoneTenths,
        settings::setDeadzoneTenths);
    toggle("Limit overshoot", -1, y + 96, settings::isLimitOvershoot, settings::setLimitOvershoot);
    toggle(
        "Snap to mouse sensitivity",
        -1,
        y + 120,
        settings::isQuantizeOutput,
        settings::setQuantizeOutput);
  }

  private int walkingLimit() {
    int rows = WalkingSettings.Control.values().length + 5;
    for (var control : WalkingSettings.Control.values()) {
      if (walkingGroup(control) != null) rows++;
    }
    return Math.max(
        0, rows * ROTATION_STEP + HEADER_HEIGHT + 12 - sections.get(Tab.PATHFINDER).height());
  }

  private void addWalkingControls() {
    walkingScroll = Math.clamp(walkingScroll, 0, walkingLimit());
    var settings = RyznSettings.get().pathfinder();
    var walking = settings.walking();
    toggle("Sprint", -1, 8, settings::isSprint, settings::setSprint);
    toggle("Constant jumping", -1, 32, settings::isConstantJumping, settings::setConstantJumping);
    toggle("Draw full route", -1, 56, settings::isPathDrawInstant, settings::setPathDrawInstant);
    toggle("Ryzn Trace", 0, 80, DebugControl::isEnabled, DebugControl::setEnabled);
    toggle("Log to file only", 1, 80, settings::isPathDebugLogOnly, settings::setPathDebugLogOnly);
    int y = WALKING_CONTROLS_TOP;
    for (var control : WalkingSettings.Control.values()) {
      if (walkingGroup(control) != null) y += ROTATION_STEP;
      if (control.min == 0 && control.max == 1) {
        toggle(
            control.label,
            -1,
            y,
            () -> walking.enabled(control),
            value -> walking.set(control, value ? 1 : 0));
      } else {
        slider(
            control.label,
            -1,
            y,
            control.min,
            control.max,
            control.unit,
            () -> walking.get(control),
            value -> walking.set(control, value));
      }
      y += ROTATION_STEP;
    }
    addRenderableWidget(
        new RyznMenuStyle.MenuButton(
            font,
            "Reset walking controls",
            contentX,
            settingsY(y),
            contentWidth,
            settingsRowHeight(),
            false,
            () -> {
              walking.reset();
              RyznSettings.get().save();
              rebuildWidgets();
            }));
  }

  private static String walkingGroup(WalkingSettings.Control control) {
    return switch (control) {
      case PREVIEW -> "Movement";
      case GAZE_LEAD -> "Gaze";
      case YAW_CAP -> "Route camera";
      case ARRIVAL_COAST -> "Arrival";
      case DROP_LEAD -> "Terrain & recovery";
      case TRACE -> "Diagnostics";
      default -> null;
    };
  }

  private void addHudControls() {
    var cfg = RyznSettings.get();
    toggle("Status HUD", 0, 32, cfg.statusHud()::isEnabled, cfg.statusHud()::setEnabled);
    toggle(
        "Show coordinates", 1, 32, cfg.statusCoords()::isEnabled, cfg.statusCoords()::setEnabled);
  }

  private void addDeveloperControls() {
    var cfg = RyznSettings.get();
    var settings = cfg.pathfinder();
    toggle("Move in menu", -1, 8, cfg.ryznMenu()::isMoveInMenu, cfg.ryznMenu()::setMoveInMenu);
    toggle(
        "Custom GUI delay",
        -1,
        57,
        settings::isGuiActionDelayCustom,
        settings::setGuiActionDelayCustom);
    slider(
        "GUI delay min",
        0,
        81,
        2000,
        " ms",
        settings::getGuiActionDelayMinMs,
        settings::setGuiActionDelayMinMs);
    slider(
        "GUI delay max",
        1,
        81,
        2000,
        " ms",
        settings::getGuiActionDelayMaxMs,
        settings::setGuiActionDelayMaxMs);
    var harp = RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class);
    slider("Harp retry delay", 0, 130, 10, 100, " ticks", harp::getRetryDelay, harp::setRetryDelay);
    slider("Harp minimum score", 1, 130, 50, 100, "%", harp::getMinScore, harp::setMinScore);
    addWheatControls(top + 170);
  }

  private int settingsY(int offset) {
    if (buildingSection == Tab.PATHFINDER) return top + offset - walkingScroll;
    if (buildingSection == Tab.ROTATIONS) return top + offset - rotationScroll;
    return top + offset * settingsHeight() / 220;
  }

  private int settingsHeight() {
    if (buildingSection == Tab.DEVELOPER) return 220;
    return Math.min(220, panelHeight - HEADER_HEIGHT - 8);
  }

  private int settingsRowHeight() {
    return Math.max(11, Math.min(20, settingsHeight() * 24 / 220 - 2));
  }

  private void toggle(String label, int column, int y, BooleanSupplier get, Consumer<Boolean> set) {
    int rowWidth = column < 0 ? contentWidth : (contentWidth - 6) / 2;
    addRenderableWidget(
        new RyznMenuStyle.MenuButton(
            font,
            label + (get.getAsBoolean() ? "  ·  On" : "  ·  Off"),
            contentX + Math.max(0, column) * (rowWidth + 6),
            settingsY(y),
            rowWidth,
            settingsRowHeight(),
            get.getAsBoolean(),
            () -> {
              set.accept(!get.getAsBoolean());
              RyznSettings.get().save();
              rebuildWidgets();
            }));
  }

  private void slider(String label, int column, int y, IntSupplier get, IntConsumer set) {
    slider(label, column, y, 200, "%", get, set);
  }

  private void slider(
      String label, int column, int y, int max, String unit, IntSupplier get, IntConsumer set) {
    slider(label, column, y, 0, max, unit, get, set);
  }

  private void slider(
      String label,
      int column,
      int y,
      int min,
      int max,
      String unit,
      IntSupplier get,
      IntConsumer set) {
    sliderAt(label, column, settingsY(y), settingsRowHeight(), min, max, unit, get, set);
  }

  private void sliderAt(
      String label,
      int column,
      int y,
      int height,
      int min,
      int max,
      String unit,
      IntSupplier get,
      IntConsumer set) {
    int rowWidth = column < 0 ? contentWidth : (contentWidth - 6) / 2;
    int range = max - min;
    var control =
        new AbstractSliderButton(
            contentX + Math.max(0, column) * (rowWidth + 6),
            y,
            rowWidth,
            height,
            Component.literal(label + "  " + get.getAsInt() + unit),
            (double) (get.getAsInt() - min) / range) {
          @Override
          public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            RyznMenuStyle.rounded(
                g, x, y, w, h, isFocused() || isHovered() ? RyznTheme.SELECTION : RyznTheme.CARD);
            int filled = (int) Math.round((w - 8) * ((double) (get.getAsInt() - min) / range));
            g.fill(x + 4, y + h - 3, x + 4 + filled, y + h - 2, RyznTheme.ACCENT);
            RyznMenuStyle.controlText(
                g,
                font,
                label + "  " + get.getAsInt() + unit,
                x + 4,
                y,
                w - 8,
                h - 2,
                RyznTheme.INK,
                false,
                true);
          }

          @Override
          protected void updateMessage() {
            setMessage(Component.literal(label + "  " + get.getAsInt() + unit));
          }

          @Override
          protected void applyValue() {
            set.accept(min + (int) Math.round(value * range));
            RyznSettings.get().save();
          }
        };
    if (font.width(control.getMessage().getString()) > rowWidth - 8) {
      control.setTooltip(Tooltip.create(Component.literal(label)));
    }
    addRenderableWidget(control);
  }

  @Override
  public void tick() {
    if (levelToggle != null) {
      levelToggle.setMessage(Component.literal(levelLabel()));
      levelToggle.active = minecraft.player != null;
      freshStart.active = minecraft.player != null && !RyznSettings.get().level7().isActive();
      freshStart.visible = !RyznSettings.get().level7().isActive();
    }
  }

  private RyznMenuStyle.MenuButton button(
      String label, int x, int y, int w, boolean primary, Runnable action) {
    return button(label, x, y, w, primary, RyznMenuStyle.ButtonKind.ACTION, action);
  }

  private RyznMenuStyle.MenuButton button(
      String label,
      int x,
      int y,
      int w,
      boolean primary,
      RyznMenuStyle.ButtonKind kind,
      Runnable action) {
    return addRenderableWidget(
        new RyznMenuStyle.MenuButton(font, label, x, y, w, 20, primary, kind, action));
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
    // The workspace draws its chrome and scroll viewport in separate passes.
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
    int menuMouseX = confirmingReset ? -1 : mouseX;
    int menuMouseY = confirmingReset ? -1 : mouseY;
    g.fill(0, 0, width, height, 0x40090a12);
    g.enableScissor(0, workspaceTop(), width, height);
    try {
      Tab hovered = sectionAt(menuMouseX, menuMouseY);
      for (Tab page : frontOrder) {
        SectionBounds bounds = sections.get(page);
        if (bounds.y() + bounds.height() <= workspaceTop() || bounds.y() >= height) continue;
        useSection(page);
        int panelMouseX = page == hovered ? mouseX : -1;
        int panelMouseY = page == hovered ? mouseY : -1;
        if (openSections.contains(page)) {
          drawPanelBackground(g);
          g.enableScissor(
              bounds.x(), top, bounds.x() + bounds.width(), bounds.y() + bounds.height());
          try {
            for (var control : panelWidgets.getOrDefault(page, List.of())) {
              control.extractRenderState(g, panelMouseX, panelMouseY, tick);
            }
            drawPanelLabels(g, page);

          } finally {
            g.disableScissor();
          }
        }
        headerButtons.get(page).extractRenderState(g, panelMouseX, panelMouseY, tick);
      }
      if (maxScroll > 0) {
        int track = height - workspaceTop() - MARGIN;
        int thumb = Math.max(18, track * track / (track + maxScroll));
        int thumbY = workspaceTop() + (track - thumb) * scroll / maxScroll;
        RyznMenuStyle.rounded(g, width - 5, thumbY, 3, thumb, RyznTheme.MUTED);
      }
    } finally {
      g.disableScissor();
    }
    RyznMenuStyle.rounded(g, MARGIN, 6, width - 2 * MARGIN, toolbarBottom() - 6, RyznTheme.TOP);
    RyznMenuStyle.crown(g, MARGIN + 8, singleRowToolbar() ? 9 : 7, 35, 28);
    RyznMenuStyle.text(
        g,
        font,
        "RYZN",
        MARGIN + 49,
        singleRowToolbar() ? 18 : 16,
        48,
        RyznTheme.ACCENT,
        true,
        false);
    if (!configStatus.isEmpty() || width >= 680) {
      boolean inline = singleRowToolbar();
      RyznMenuStyle.text(
          g,
          font,
          configStatus.isEmpty() ? "Drag section headers to move" : configStatus,
          MARGIN + (inline ? 92 : 8),
          inline ? 18 : 42,
          Math.max(0, width - (inline ? 502 : 216)),
          RyznTheme.MUTED,
          false,
          false);
    }
    for (var control : toolbarButtons) control.extractRenderState(g, menuMouseX, menuMouseY, tick);
    if (confirmingReset) drawResetDialog(g, mouseX, mouseY, tick);
  }

  private void drawResetDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
    int dialogWidth = resetDialogWidth();
    int x = (width - dialogWidth) / 2;
    int y = height / 2 - 48;
    g.nextStratum();
    g.fill(0, 0, width, height, 0x54090a12);
    RyznMenuStyle.glass(g, x, y, dialogWidth, 100, RyznTheme.TOP, RyznTheme.BOTTOM, 9);
    RyznMenuStyle.text(
        g,
        font,
        "Reset all Ryzn settings?",
        x + 12,
        y + 12,
        dialogWidth - 24,
        RyznTheme.ACCENT,
        true,
        true);
    RyznMenuStyle.text(
        g,
        font,
        "Active macros will stop and saved settings",
        x + 12,
        y + 32,
        dialogWidth - 24,
        RyznTheme.INK,
        false,
        true);
    RyznMenuStyle.text(
        g,
        font,
        "will be replaced with defaults.",
        x + 12,
        y + 45,
        dialogWidth - 24,
        RyznTheme.MUTED,
        false,
        true);
    cancelReset.extractRenderState(g, mouseX, mouseY, tick);
    applyReset.extractRenderState(g, mouseX, mouseY, tick);
  }

  private void drawPanelBackground(GuiGraphicsExtractor g) {
    int panelTop = top - HEADER_HEIGHT;
    RyznMenuStyle.glass(
        g, left, panelTop, panelWidth, panelHeight, RyznTheme.TOP, RyznTheme.BOTTOM, 9);
  }

  private void drawPanelLabels(GuiGraphicsExtractor g, Tab page) {
    switch (page) {
      case LEVEL_7 -> {
        boolean wide = panelWidth >= 560;
        int stageX = wide ? contentX + (contentWidth - 16) * 2 / 5 + 16 : contentX;
        int stageY =
            wide ? top + 26 : top + 26 + (AutoLvl7Macro.routeCheckpointOrder().length + 2) * 24;
        line(g, "Route checkpoints", top + 26);
        RyznMenuStyle.text(
            g,
            font,
            "Stages in selected checkpoint",
            stageX,
            stageY,
            contentX + contentWidth - stageX,
            RyznTheme.MUTED,
            false,
            false);
      }
      case DEVELOPER -> {
        line(g, "GUI action timing", settingsY(40));
        line(g, "Auto Harp", settingsY(113));
      }
      case PATHFINDER -> {
        int offset = WALKING_CONTROLS_TOP;
        for (var control : WalkingSettings.Control.values()) {
          String group = walkingGroup(control);
          if (group != null) {
            line(g, group, settingsY(offset));
            offset += ROTATION_STEP;
          }
          offset += ROTATION_STEP;
        }
        int limit = walkingLimit();
        if (limit > 0) {
          int track = panelHeight - HEADER_HEIGHT - 12;
          int thumb = Math.max(15, track * track / (track + limit));
          int y = top + 5 + (track - thumb) * walkingScroll / limit;
          RyznMenuStyle.rounded(g, left + panelWidth - 5, y, 2, thumb, RyznTheme.MUTED);
        }
      }
      case ROTATIONS -> {
        line(g, "ABCurves engine · " + AbcRotation.status(), settingsY(32));
        int limit = rotationLimit();
        if (limit > 0) {
          int track = panelHeight - HEADER_HEIGHT - 12;
          int thumb = Math.max(15, track * track / (track + limit));
          int y = top + 5 + (track - thumb) * rotationScroll / limit;
          RyznMenuStyle.rounded(g, left + panelWidth - 5, y, 2, thumb, RyznTheme.MUTED);
        }
      }
      default -> {}
    }
  }

  private void line(GuiGraphicsExtractor g, String text, int y) {
    RyznMenuStyle.text(g, font, text, contentX, y, contentWidth, RyznTheme.MUTED, false, false);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (confirmingReset) {
      if (!cancelReset.mouseClicked(event, doubleClick))
        applyReset.mouseClicked(event, doubleClick);
      return true;
    }
    if (event.y() < workspaceTop()) {
      for (var control : toolbarButtons) {
        if (control.mouseClicked(event, doubleClick)) return true;
      }
      return false;
    }
    Tab page = sectionAt(event.x(), event.y());
    if (page == null) {
      setFocused(null);
      return false;
    }
    SectionBounds bounds = sections.get(page);
    frontOrder.remove(page);
    frontOrder.add(page);
    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
        && bounds.containsHeader(event.x(), event.y())) {
      pressedHeader = page;
      pressX = (int) event.x();
      pressY = (int) event.y();
      dragOffsetX = pressX - bounds.x();
      dragOffsetY = pressY - bounds.y();
      draggingHeader = false;
      setFocused(null);
      setDragging(true);
      return true;
    }

    for (var control : panelWidgets.getOrDefault(page, List.of())) {
      if (control.mouseClicked(event, doubleClick)) {
        if (children().contains(control)) {
          setFocused(control);
          setDragging(event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        return true;
      }
    }
    setFocused(null);
    return true;
  }

  private Tab sectionAt(double x, double y) {
    if (y < workspaceTop() || y >= height) return null;
    for (int i = frontOrder.size() - 1; i >= 0; i--) {
      Tab page = frontOrder.get(i);
      if (sections.get(page).contains(x, y)) return page;
    }
    return null;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    if (confirmingReset) return true;
    if (pressedHeader == null) return super.mouseDragged(event, dx, dy);
    if (!draggingHeader && Math.abs(pressX - event.x()) + Math.abs(pressY - event.y()) < 4)
      return true;
    draggingHeader = true;
    SectionBounds bounds = sections.get(pressedHeader);
    int availableX = travelX(bounds);
    int availableY = travelY(bounds);
    int x = Math.clamp((int) event.x() - dragOffsetX - MARGIN, 0, availableX);
    int y = Math.clamp((int) event.y() - dragOffsetY - workspaceTop(), 0, availableY);
    var position =
        new Position(
            availableX == 0 ? 0 : Math.round(x * 1000f / availableX),
            headerTravelY() == 0 ? 0 : Math.round(y * 1000f / headerTravelY()));
    var settings = RyznSettings.get().ryznMenu();
    if (!position.equals(settings.position(pressedHeader.id()))) {
      settings.setPosition(pressedHeader.id(), position);
      rebuildWidgets();
      setDragging(true);
    }
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    if (confirmingReset) return true;
    if (pressedHeader == null || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      return super.mouseReleased(event);
    }
    Tab page = pressedHeader;
    pressedHeader = null;
    setDragging(false);
    if (draggingHeader) {
      draggingHeader = false;
      RyznSettings.get().save();
    } else if (sections.get(page).containsHeader(event.x(), event.y())) {
      toggleSection(page);
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (confirmingReset) return true;
    if (scrollY == 0 || y < workspaceTop()) return false;
    Tab page = sectionAt(x, y);

    if (page == Tab.PATHFINDER && openSections.contains(page)) {
      int next =
          Math.clamp(walkingScroll - (int) Math.round(scrollY * ROTATION_STEP), 0, walkingLimit());
      if (next != walkingScroll) {
        walkingScroll = next;
        rebuildWidgets();
        return true;
      }
    }
    if (page == Tab.ROTATIONS && openSections.contains(page)) {
      int next =
          Math.clamp(
              rotationScroll - (int) Math.round(scrollY * ROTATION_STEP), 0, rotationLimit());
      if (next != rotationScroll) {
        rotationScroll = next;
        rebuildWidgets();
        return true;
      }
    }
    int next = Math.clamp(scroll - (int) Math.round(scrollY * 34), 0, maxScroll);
    if (next == scroll) return false;
    scroll = next;
    rebuildWidgets();
    return true;
  }

  @Override
  public void removed() {
    if (draggingHeader) RyznSettings.get().save();
    super.removed();
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (confirmingReset) {
      if (event.key() == GLFW.GLFW_KEY_ESCAPE) confirmingReset = false;
      return true;
    }
    return event.key() == GLFW.GLFW_KEY_SPACE || super.keyPressed(event);
  }

  @Override
  public boolean isPauseScreen() {
    return parent != null && parent.isPauseScreen();
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
    // Minecraft restores its pre-screen toggle when gameplay regains focus.
    // Apply the menu's choice after that restoration so the next input tick agrees.
    if (minecraft.gui.screen() == null
        && sprintToggleChanged
        && minecraft.options.toggleSprint().get()
        && minecraft.options.keySprint.isDown() != sprintToggled) {
      minecraft.options.keySprint.setDown(true);
    }
  }
}
