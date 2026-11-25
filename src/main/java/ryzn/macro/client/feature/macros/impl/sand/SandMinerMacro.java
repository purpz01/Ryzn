package ryzn.macro.client.feature.macros.impl.sand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.SandMinerActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.inventory.VanillaTools;
import ryzn.macro.client.util.text.MacroText;
import ryzn.macro.client.util.text.RyznChat;

/** Follows a saved coordinate loop while holding attack and looking roughly 45 degrees down. */
public final class SandMinerMacro extends Macro implements ConfigSection, MenuToggle {

  private static final float MINING_PITCH = 45.0f;

  /** Pitch wanders this far either side of 45 instead of sitting on it. */
  private static final float PITCH_WANDER = 4.0f;

  /** Yaw wobble around the route heading, in degrees, while the body is on the line. */
  private static final float YAW_WOBBLE = 4.5f;

  /** Off the line by this much and the wobble fades out, so steering wins and pulls back. */
  private static final double LINE_TOLERANCE = 0.6;

  /** The heading aims this far ahead along the segment, which steers back onto the line. */
  private static final double LOOKAHEAD = 2.5;

  /** Each lap picks a new spot this far around each waypoint, still inside its reach radius. */
  private static final double WAYPOINT_JITTER = 0.4;

  private static final int WANDER_MIN_TICKS = 25;
  private static final int WANDER_MAX_TICKS = 80;
  private static final float WANDER_EASE = 0.06f;
  private static final double WAYPOINT_REACHED_SQ = 1.5 * 1.5;
  private static final double AIM_YAW_DEG_PER_SEC = 260.0;
  private static final double AIM_PITCH_DEG_PER_SEC = 180.0;
  private static final float AIM_DEADZONE = 0.5f;
  private static final float YAW_SMOOTH_FACTOR = 0.24f;
  private static final float PITCH_SMOOTH_FACTOR = 0.18f;

  private static final List<int[]> defaultRoute =
      List.of(
          new int[] {194, 77, -381},
          new int[] {202, 77, -376},
          new int[] {211, 77, -383},
          new int[] {213, 77, -392},
          new int[] {203, 77, -400},
          new int[] {192, 77, -396},
          new int[] {186, 77, -388});

  private static final List<List<int[]>> retiredDefaults =
      List.of(
          List.of(
              new int[] {202, 77, -362},
              new int[] {195, 77, -350},
              new int[] {203, 77, -341},
              new int[] {208, 77, -346},
              new int[] {214, 77, -353},
              new int[] {208, 77, -358}),
          List.of(
              new int[] {221, 76, -366},
              new int[] {227, 76, -360},
              new int[] {237, 76, -357},
              new int[] {244, 76, -366},
              new int[] {237, 76, -369},
              new int[] {230, 76, -366}));
  private final List<int[]> customRoute = new ArrayList<>();
  private final List<List<int[]>> extraRoutes = new ArrayList<>();
  private final Setting<Integer> selectedRoute =
      addSetting(Setting.integer("sand_selected_route", "Route", 0, 0, 31));
  private final Setting<Boolean> randomRoute =
      addSetting(Setting.bool("sand_random_route", "Random route on start", true));
  private int preparedRoute = -1;
  private List<int[]> activeRoute;
  private boolean joiningRoute;
  private int joinTicks;

  private final SandEnchantedCraft craft = new SandEnchantedCraft();

  /** Enchanted Sand crafting stays off until the Sand collection reaches this level. */
  private static final int CRAFT_MIN_COLLECTION_LEVEL = 5;

  /**
   * " §6§lCOLLECTION LEVEL UP §eSand §8IV➜§eV" - colours stripped, the last numeral is the new
   * level.
   */
  private static final Pattern COLLECTION_LEVEL_UP =
      Pattern.compile("COLLECTION LEVEL UP\s+(.+?)\s+(?:[IVXLC]+➜)?([IVXLC]+)\s*$");

  private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

  private final Setting<Boolean> enabled =
      addSetting(Setting.bool("sand_miner_enabled", "sandMinerEnabled", false));
  private final Setting<Boolean> showRoute =
      addSetting(Setting.bool("sand_show_route", "sandShowRoute", true));
  private final Setting<Boolean> craftEnchantedEnabled =
      addSetting(Setting.bool("sand_craft_enchanted_enabled", "sandCraftEnchantedEnabled", false));
  private final Setting<Integer> sandCollectionLevel =
      addSetting(
          Setting.integer(
              "sand_collection_level",
              "sandCollectionLevel",
              0,
              Integer.MIN_VALUE,
              Integer.MAX_VALUE));
  private int routeIndex = -1;
  private boolean targetInitialized;
  private boolean inputsHeld;
  private float targetYaw;
  private float targetPitch = MINING_PITCH;
  private float yawWobble;
  private float yawWobbleGoal;
  private float pitchOffset;
  private float pitchOffsetGoal;
  private int wanderTicks;
  private double jitterX;
  private double jitterZ;
  private String status = "Disabled";
  private String target = "No target";

  public SandMinerMacro() {
    super(
        "sand_miner",
        "Sand Miner",
        "Loops a saved coordinate route while looking 45 degrees down and continuously mining.");
  }

  @Override
  public void read(JsonObject object) {
    enabled.read(object);
    if (object.has("sand_show_route")) {
      showRoute.read(object);
    } else {
      showRoute.set(
          object.get("sand_show_zone") instanceof JsonPrimitive value && value.isBoolean()
              ? value.getAsBoolean()
              : true);
    }
    craftEnchantedEnabled.read(object);
    sandCollectionLevel.read(object);
    JsonElement points =
        object.has("sand_route_points")
            ? object.get("sand_route_points")
            : object.get("sand_custom_perimeter_points");
    setRouteFromConfig(readPoints(points));
    selectedRoute.read(object);
    randomRoute.read(object);
    extraRoutes.clear();
    if (object.get("sand_extra_routes") instanceof JsonArray routes) {
      for (JsonElement route : routes) {
        if (extraRoutes.size() >= 31) {
          break;
        }
        extraRoutes.add(new ArrayList<>(readPoints(route)));
      }
    }
    if (!object.has("sand_route_defaults_version")
        && customRoute.isEmpty()
        && extraRoutes.size() == 3
        && sameRoute(extraRoutes.get(0), defaultRoute)
        && sameRoute(extraRoutes.get(1), retiredDefaults.get(0))
        && sameRoute(extraRoutes.get(2), retiredDefaults.get(1))) {
      setRouteFromConfig(extraRoutes.removeFirst());
      selectedRoute.set(Math.max(0, selectedRoute.get() - 1));
    }
    for (int i = extraRoutes.size() - 1; i >= 0; i--) {
      List<int[]> route = extraRoutes.get(i);
      if (retiredDefaults.stream().anyMatch(retired -> sameRoute(route, retired))) {
        extraRoutes.remove(i);
        int selected = selectedRoute.get();
        if (selected == i + 1) selectedRoute.set(0);
        else if (selected > i + 1) selectedRoute.set(selected - 1);
      }
    }
    selectedRoute.set(Math.min(selectedRoute.get(), extraRoutes.size()));
  }

  private static boolean sameRoute(List<int[]> route, List<int[]> other) {
    return java.util.Arrays.deepEquals(route.toArray(), other.toArray());
  }

  @Override
  public JsonObject write() {
    JsonObject object = writeSettings();
    object.addProperty("sand_selected_route", Math.min(selectedRoute.get(), extraRoutes.size()));
    JsonArray points = new JsonArray();
    for (int[] point : customRouteForConfig()) {
      JsonArray encoded = new JsonArray();
      encoded.add(point[0]);
      encoded.add(point[1]);
      encoded.add(point[2]);
      points.add(encoded);
    }
    object.add("sand_route_points", points);
    JsonArray routes = new JsonArray();
    for (List<int[]> route : extraRoutes) {
      JsonArray encodedRoute = new JsonArray();
      for (int[] point : route) {
        JsonArray encoded = new JsonArray();
        encoded.add(point[0]);
        encoded.add(point[1]);
        encoded.add(point[2]);
        encodedRoute.add(encoded);
      }
      routes.add(encodedRoute);
    }
    object.add("sand_extra_routes", routes);
    object.addProperty("sand_route_defaults_version", 1);
    return object;
  }

  public void applyConfig() {
    preparedRoute = -1;
    resetSession();
  }

  public void onClientTick(Minecraft mc) {
    if (!enabled.get()) {
      stopInputs(mc);
      status = "Disabled";
      return;
    }
    LocalPlayer player = mc.player;
    if (player == null || mc.level == null || mc.gameMode == null) {
      stopInputs(mc);
      return;
    }
    if (activeRoute == null && mc.gui.screen() != null) {
      return;
    }
    if (activeRoute == null) {
      chooseStartRoute(player);
      if (!enabled.get()) {
        return;
      }
    }
    if (joiningRoute) {
      targetInitialized = false;
      MacroInput.setAttackHeld(mc.options, false);
      if (mc.gui.screen() != null) {
        PathfindCommand.stopNavQuiet();
        joiningRoute = false;
        activeRoute = null;
        return;
      }
      int[] entry = activeRoute.get(routeIndex);
      if (reached(entry, player.getX(), player.getZ())
          && Math.abs(player.getY() - entry[1]) <= 1.5) {
        PathfindCommand.stopNavQuiet();
        joiningRoute = false;
        pickWaypointJitter();
      } else {
        status = "Pathfinding to route start";
        if (++joinTicks >= 1200 || !PathfindCommand.isNavigationActive()) {
          setEnabled(false);
          RyznChat.send("Sand Miner could not reach its route start.");
        }
        return;
      }
    }
    if (craftEnchantedEnabled.get()
        && sandCollectionLevel.get() >= CRAFT_MIN_COLLECTION_LEVEL
        && craft.tick(mc, player)) {
      stopInputs(mc); // the craft owns the player until it hands back
      status = craft.status();
      return;
    }
    if (mc.gui.screen() != null) {
      stopInputs(mc);
      return;
    }

    List<int[]> route = routePoints();
    if (route.size() < 2) {
      stopInputs(mc);
      status = "Add at least 2 route points";
      target = "No target";
      return;
    }
    if (routeIndex < 0 || routeIndex >= route.size()) {
      routeIndex = nearestRouteIndex(route, player.getX(), player.getZ());
      pickWaypointJitter();
    }

    int[] point = route.get(routeIndex);
    if (reached(point, player.getX(), player.getZ())) {
      routeIndex = nextRouteIndex(routeIndex, route.size());
      point = route.get(routeIndex);
      pickWaypointJitter();
    }

    int[] previous = route.get(Math.floorMod(routeIndex - 1, route.size()));
    double offLine = steer(player.getX(), player.getZ(), previous, point);
    wander();
    // The wobble only plays while the body is on the line; drifting off hands back to the steer.
    float onLine = (float) Mth.clamp(1.0 - offLine / LINE_TOLERANCE, 0.0, 1.0);
    targetYaw = Mth.wrapDegrees(targetYaw + yawWobble * onLine);
    targetPitch = MINING_PITCH + pitchOffset;
    targetInitialized = true;
    target =
        "Point "
            + (routeIndex + 1)
            + "/"
            + route.size()
            + "  "
            + point[0]
            + ", "
            + point[1]
            + ", "
            + point[2];
    status = "Following route and mining at 45 degrees";

    holdShovel(player);

    Options options = mc.options;
    inputsHeld = true;
    MacroKeys.set(options.keyUp, true);
    MacroKeys.set(options.keyDown, false);
    MacroKeys.set(options.keyLeft, false);
    MacroKeys.set(options.keyRight, false);
    MacroKeys.set(options.keyJump, false);
    MacroKeys.set(options.keyShift, false);
    MacroKeys.set(options.keySprint, false);
    MacroInput.setAttackHeld(options, true);
  }

  /**
   * Sand needs a shovel. The route arrives straight off the Gold Shovel run, which finishes holding
   * the axe it just enchanted, so the tool is picked here rather than assumed from whatever came
   * before.
   */
  private static void holdShovel(LocalPlayer player) {
    if (VanillaTools.isShovel(player.getMainHandItem())) {
      return;
    }
    int slot = MacroSlots.findHotbarSlot(player, VanillaTools::isShovel);
    if (slot >= 0) {
      MacroInput.selectHotbarSlot(player, slot);
    }
  }

  public void tickLook() {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (!enabled.get() || player == null || !targetInitialized || mc.gui.screen() != null) {
      RyznBootstrap.rotationManager().releaseTurn(this);
      return;
    }
    RyznBootstrap.rotationManager()
        .smoothTurn(
            this,
            player,
            targetYaw,
            targetPitch,
            AIM_DEADZONE,
            YAW_SMOOTH_FACTOR,
            PITCH_SMOOTH_FACTOR,
            AIM_YAW_DEG_PER_SEC,
            AIM_PITCH_DEG_PER_SEC);
  }

  public void onRenderFrame() {
    tickLook();
  }

  /**
   * Points the heading at a spot {@link #LOOKAHEAD} blocks ahead along the segment the body is on,
   * so any sideways drift is steered back onto the route.
   *
   * @return how far the body is from the segment's line, in blocks
   */
  private double steer(double x, double z, int[] from, int[] to) {
    double ax = from[0] + 0.5;
    double az = from[2] + 0.5;
    double bx = to[0] + 0.5 + jitterX;
    double bz = to[2] + 0.5 + jitterZ;
    double dx = bx - ax;
    double dz = bz - az;
    double length = Math.sqrt(dx * dx + dz * dz);
    if (length < 1.0e-3) {
      targetYaw = yawTo(x, z, bx, bz);
      return 0.0;
    }
    double ux = dx / length;
    double uz = dz / length;
    double along = Mth.clamp((x - ax) * ux + (z - az) * uz, 0.0, length);
    double aim = Math.min(length, along + LOOKAHEAD);
    targetYaw = yawTo(x, z, ax + ux * aim, az + uz * aim);
    return Math.abs((x - ax) * uz - (z - az) * ux);
  }

  /** Slowly drifting yaw and pitch offsets: a new goal every 1-4 s, eased toward, never snapped. */
  private void wander() {
    if (--wanderTicks <= 0) {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      wanderTicks = random.nextInt(WANDER_MIN_TICKS, WANDER_MAX_TICKS + 1);
      yawWobbleGoal = (float) random.nextDouble(-YAW_WOBBLE, YAW_WOBBLE);
      pitchOffsetGoal = (float) random.nextDouble(-PITCH_WANDER, PITCH_WANDER);
    }
    yawWobble += (yawWobbleGoal - yawWobble) * WANDER_EASE;
    pitchOffset += (pitchOffsetGoal - pitchOffset) * WANDER_EASE;
  }

  private void pickWaypointJitter() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    jitterX = random.nextDouble(-WAYPOINT_JITTER, WAYPOINT_JITTER);
    jitterZ = random.nextDouble(-WAYPOINT_JITTER, WAYPOINT_JITTER);
  }

  List<int[]> routePoints() {
    return Collections.unmodifiableList(
        activeRoute == null ? savedRoute(selectedRoute.get()) : activeRoute);
  }

  private List<int[]> savedRoute(int index) {
    return index == 0
        ? (customRoute.isEmpty() ? defaultRoute : customRoute)
        : extraRoutes.get(index - 1);
  }

  private List<int[]> editableRoute() {
    return selectedRoute.get() == 0 ? customRoute : extraRoutes.get(selectedRoute.get() - 1);
  }

  private void chooseStartRoute(LocalPlayer player) {
    int chosen =
        preparedRoute >= 0
            ? preparedRoute
            : randomRoute.get() ? randomValidRoute() : selectedRoute.get();
    preparedRoute = -1;
    activeRoute = new ArrayList<>();
    for (int[] point : savedRoute(chosen)) {
      activeRoute.add(point.clone());
    }
    if (activeRoute.size() < 2) {
      return;
    }
    double best = Double.POSITIVE_INFINITY;
    for (int i = 0; i < activeRoute.size(); i++) {
      int[] point = activeRoute.get(i);
      double distance = player.distanceToSqr(point[0] + 0.5, point[1], point[2] + 0.5);
      if (distance < best) {
        best = distance;
        routeIndex = i;
      }
    }
    int[] entry = activeRoute.get(routeIndex);
    stopInputs(Minecraft.getInstance());
    joiningRoute = PathfindCommand.startToQuiet(entry[0], entry[1], entry[2]);
    joinTicks = 0;
    if (!joiningRoute) {
      setEnabled(false);
      RyznChat.send("Sand Miner could not start navigation to its route.");
    }
  }

  private int randomValidRoute() {
    List<Integer> valid = new ArrayList<>();
    for (int i = 0; i <= extraRoutes.size(); i++) {
      if (savedRoute(i).size() >= 2) {
        valid.add(i);
      }
    }
    return valid.isEmpty()
        ? selectedRoute.get()
        : valid.get(ThreadLocalRandom.current().nextInt(valid.size()));
  }

  public boolean hasPreparedRoute() {
    return preparedRoute >= 0;
  }

  public int[] prepareRandomRoute(LocalPlayer player) {
    preparedRoute = randomValidRoute();
    List<int[]> route = savedRoute(preparedRoute);
    int[] nearest = null;
    double best = Double.POSITIVE_INFINITY;
    for (int[] point : route) {
      double distance = player.distanceToSqr(point[0] + 0.5, point[1], point[2] + 0.5);
      if (distance < best) {
        best = distance;
        nearest = point;
      }
    }
    if (nearest == null) {
      preparedRoute = -1;
      return null;
    }
    return nearest.clone();
  }

  int currentRouteIndex() {
    return routeIndex;
  }

  static int nextRouteIndex(int current, int size) {
    return size <= 0 ? -1 : (current + 1) % size;
  }

  static int nearestRouteIndex(List<int[]> route, double x, double z) {
    int nearest = 0;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (int i = 0; i < route.size(); i++) {
      int[] point = route.get(i);
      double dx = point[0] + 0.5 - x;
      double dz = point[2] + 0.5 - z;
      double distance = dx * dx + dz * dz;
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = i;
      }
    }
    return nearest;
  }

  public int[] nearestRoutePoint(double x, double z) {
    return nearestRoutePoint(routePoints(), x, z);
  }

  static int[] nearestRoutePoint(List<int[]> route, double x, double z) {
    return route.get(nearestRouteIndex(route, x, z)).clone();
  }

  static boolean reached(int[] point, double x, double z) {
    double dx = point[0] + 0.5 - x;
    double dz = point[2] + 0.5 - z;
    return dx * dx + dz * dz <= WAYPOINT_REACHED_SQ;
  }

  private static float yawTo(double fromX, double fromZ, double toX, double toZ) {
    return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0f);
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public void setEnabled(boolean on) {
    if (!on) {
      preparedRoute = -1;
    }
    if (enabled.get() == on) {
      persist();
      return;
    }
    int pendingRoute = preparedRoute;
    enabled.set(on);
    RyznChat.announce("Sand Miner", on);
    stopInputs(Minecraft.getInstance());
    resetSession();
    if (on) {
      preparedRoute = pendingRoute;
    }
    persist();
  }

  public boolean isShowRoute() {
    return showRoute.get();
  }

  public void setShowRoute(boolean on) {
    showRoute.set(on);
    persist();
  }

  public void setCraftEnchantedEnabled(boolean on) {
    craftEnchantedEnabled.set(on);
    persist();
  }

  public void addRouteWaypoint() {
    LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    BlockPos point = player.blockPosition();
    if (!editableRoute().isEmpty()) {
      int[] last = editableRoute().getLast();
      if (last[0] == point.getX() && last[1] == point.getY() && last[2] == point.getZ()) {
        return;
      }
    }
    editableRoute().add(new int[] {point.getX(), point.getY(), point.getZ()});
    resetSession();
    persist();
  }

  public void removeLastRouteWaypoint() {
    if (editableRoute().isEmpty()) {
      return;
    }
    editableRoute().removeLast();
    resetSession();
    persist();
  }

  public void clearRouteWaypoints() {
    editableRoute().clear();
    resetSession();
    persist();
  }

  public void resetDefaults() {
    preparedRoute = -1;
    enabled.set(false);
    showRoute.set(true);
    craftEnchantedEnabled.set(false);
    customRoute.clear();
    extraRoutes.clear();
    selectedRoute.set(0);
    randomRoute.set(true);
    stopInputs(Minecraft.getInstance());
    resetSession();
    persist();
  }

  public String statusText() {
    return status;
  }

  public boolean shouldLockMouse() {
    return enabled.get() && targetInitialized && Minecraft.getInstance().gui.screen() == null;
  }

  public static boolean shouldSwallowManualClicks() {
    return false;
  }

  private void setRouteFromConfig(List<int[]> points) {
    customRoute.clear();
    if (points == null) {
      return;
    }
    for (int[] point : points) {
      if (point == null || point.length < 3) {
        continue;
      }
      customRoute.add(new int[] {point[0], point[1], point[2]});
    }
  }

  private List<int[]> customRouteForConfig() {
    List<int[]> points = new ArrayList<>(customRoute.size());
    for (int[] point : customRoute) {
      points.add(point.clone());
    }
    return points;
  }

  private void stopInputs(Minecraft mc) {
    if (!inputsHeld) {
      return;
    }
    if (mc != null && mc.options != null) {
      MacroInput.releaseMovementAndAttack(mc.options);
    }
    inputsHeld = false;
  }

  private void resetSession() {
    preparedRoute = -1;
    if (joiningRoute) {
      PathfindCommand.stopNavQuiet();
    }
    stopInputs(Minecraft.getInstance());
    joiningRoute = false;
    activeRoute = null;
    joinTicks = 0;
    craft.reset();
    routeIndex = -1;
    targetInitialized = false;
    targetPitch = MINING_PITCH;
    yawWobble = 0f;
    yawWobbleGoal = 0f;
    pitchOffset = 0f;
    pitchOffsetGoal = 0f;
    wanderTicks = 0;
    RyznBootstrap.rotationManager().releaseTurn(this);
    status = enabled.get() ? "Starting route" : "Disabled";
    target = "No target";
  }

  public void onGameMessageEvent(GameMessageEvent event) {
    if (event.message() != null) {
      observeChat(event.message().getString());
    }
  }

  @Subscribe
  public void onSandMinerActionEvent(SandMinerActionEvent event) {
    switch (event.action()) {
      case SET_ENABLED -> setEnabled(event.value());
      case SET_SHOW_ROUTE -> setShowRoute(event.value());
      case SET_CRAFT_ENCHANTED -> setCraftEnchantedEnabled(event.value());
      case ADD_WAYPOINT -> addRouteWaypoint();
      case REMOVE_WAYPOINT -> removeLastRouteWaypoint();
      case CLEAR_WAYPOINTS -> clearRouteWaypoints();
      case RESET_DEFAULTS -> resetDefaults();
      case RESET_COLLECTION -> resetCollectionLevel();
      default -> {}
    }
  }

  /** Latches the Sand collection level off Hypixel's level-up line; crafting unlocks at V. */
  void observeChat(String raw) {
    if (raw == null || !raw.contains("COLLECTION LEVEL UP")) {
      return;
    }
    Matcher match = COLLECTION_LEVEL_UP.matcher(FORMAT_CODE.matcher(raw).replaceAll("").trim());
    if (!match.find() || !"sand".equals(match.group(1).toLowerCase(Locale.ROOT))) {
      return;
    }
    int level = romanToInt(match.group(2));
    if (level <= sandCollectionLevel.get()) {
      return;
    }
    sandCollectionLevel.set(level);
    persist();
    if (level == CRAFT_MIN_COLLECTION_LEVEL) {
      RyznChat.send("Sand collection " + level + " - Enchanted Sand crafting unlocked");
    }
  }

  static int romanToInt(String roman) {
    return MacroText.romanToInt(roman);
  }

  /** A fresh level 7 run is a fresh profile, so the latched Sand collection starts over. */
  public void resetCollectionLevel() {
    if (sandCollectionLevel.get() == 0) {
      return;
    }
    sandCollectionLevel.set(0);
    persist();
  }

  int sandCollectionLevel() {
    return sandCollectionLevel.get();
  }

  private void persist() {
    RyznSettings cfg = RyznSettings.get();
    cfg.save();
  }

  private static List<int[]> readPoints(JsonElement element) {
    List<int[]> points = new ArrayList<>();
    if (!(element instanceof JsonArray array)) {
      return points;
    }
    for (JsonElement value : array) {
      if (!(value instanceof JsonArray point) || point.size() != 3) {
        continue;
      }
      if (!(point.get(0) instanceof JsonPrimitive x)
          || !x.isNumber()
          || !(point.get(1) instanceof JsonPrimitive y)
          || !y.isNumber()
          || !(point.get(2) instanceof JsonPrimitive z)
          || !z.isNumber()) {
        continue;
      }
      points.add(new int[] {x.getAsInt(), y.getAsInt(), z.getAsInt()});
    }
    return points;
  }

  @Override
  public boolean menuEnabled() {
    return isEnabled();
  }

  @Override
  public void menuToggle() {
    RyznBootstrap.eventBus()
        .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, !isEnabled()));
  }
}
