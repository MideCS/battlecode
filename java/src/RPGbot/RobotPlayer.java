package RPGbot;

import battlecode.common.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;


public class RobotPlayer {

    static int turnCount = 0;
    static int soldierCooldown = 0;
    static int mopperCooldown = 0;
    static boolean spawnedMopper = false;
    static HashMap<MapLocation, Integer> checked = new HashMap<>();
    static int checked_limit = 1;
    static boolean isSaving = false;
    static boolean isMessenger = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    private enum MessageType {
        SAVE_CHIPS,
        SAVE_TOWER,
    }

    private enum RobotState {
        STARTING,
        PAINTING_PATTERN,
        EXPLORING,
        ATTACKING,
    }

    static RobotState state = RobotState.STARTING;
    static MapLocation targetEnemyRuin = null;


    // Communications vars
    static int savingTurns = 0;
    static HashSet<MapLocation> exploredRuins = new HashSet<>(); // Tracks explored ruins
    static int totalRuinsVisited = 0;
    static boolean[][] paintTowerPattern = null;
    static boolean[][] moneyTowerPattern = null;

    static MapLocation paintingRuinLoc = null;
    static UnitType paintingTowerType = null;
    static int paintingTurns = 0;
    static int turnsWithoutAttack = 0;

    static final Random rng = new Random(6147);

    static final Direction[] directions = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST,
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
//        System.out.println("I'm alive");

        rc.setIndicatorString("Hello world!");

        // Delegate some soldiers as messengers
        if (rc.getType() == UnitType.SOLDIER && rc.getID() % 3 == 0) {
            isMessenger = true;
        }

        while (true) {


            turnCount += 1;  // We have now been alive for one more turn!

            try {
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break;
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break; // Consider upgrading examplefuncsplayer to use splashers!
                    default: runTower(rc); break;
                }
            }
            catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                Clock.yield();
            }
        }

    }


    public static void runTower(RobotController rc) throws GameActionException{

        // TODO: Add saving for Tower when Ruin is reached.
        if (savingTurns == 0) {
            isSaving = false;
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);

            if (soldierCooldown < 30) {
                if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)) {
                    rc.buildRobot(UnitType.SOLDIER, nextLoc);
                    //                System.out.println("BUILT A SOLDIER");
                }
            } else {
                //            System.out.println("Soldier cooldown exceeded");
                //            robotType = rng.nextInt(1,3);
                if (rc.canBuildRobot(UnitType.MOPPER, nextLoc) && mopperCooldown <= 1) {
                    rc.buildRobot(UnitType.MOPPER, nextLoc);
                    System.out.println("BUILT A MOPPER");
                    mopperCooldown++;
                } else if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)) {
                    rc.buildRobot(UnitType.SPLASHER, nextLoc);
                    rc.setIndicatorString("BUILT A SPLASHER");
                    mopperCooldown = 0;
                }
            }

        }
        else {
            rc.setIndicatorString("Saving for " + savingTurns + " more turns!");
            savingTurns --;
        }
        soldierCooldown ++;
        soldierCooldown %= 40;

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if (m.getBytes() == MessageType.SAVE_CHIPS.ordinal() && !isSaving) {
                // TODO: Broadcasts to other towers to start saving paint
//                rc.broadcastMessage(MessageType.SAVE_CHIPS.ordinal());

                savingTurns = 20;
                System.out.println("Tower received SAVE_CHIPS message. Starting to save.");
                isSaving = true;
            }

        }

        // Attack any neighboring robots
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy: enemyRobots) {
            if (rc.canAttack(enemy.location)) {
                rc.attack((enemy.location));
                break;
            }
        }
    }


    public static void runSoldier(RobotController rc) throws GameActionException{
        if (state == RobotState.STARTING) {
            if(rc.getID() % 3 == 1) {
                state = RobotState.ATTACKING;
            } else {
                state = RobotState.EXPLORING;
            }
        }

        if (state == RobotState.PAINTING_PATTERN) {
//            runPaintPattern(rc);
            paintingTurns++;
        }
        else if (state == RobotState.EXPLORING) {
            Pair<MapInfo, Integer> closestRuinWithDistance = getNearestRuin(rc);
            MapInfo closestRuin = closestRuinWithDistance.getLeft();
            int closestRuinDist = closestRuinWithDistance.getRight();

            // Basedo on ruin locations, move towards ruin and decide if we should start building a tower on ruin
            if (closestRuin != null) {
//                if (closestRuinDist > 4) bug2(rc, closestRuin.getMapLocation());
//                else {
//                    state = RobotState.PAINTING_PATTERN;
//                    paintingTowerType = getNewTowerType(rc);
//                    turnsWithoutAttack = 0;
//                    paintingTurns = 0;
//                    paintingRuinLoc = closestRuin.getMapLocation();
//                }
            }



        } else if (state == RobotState.ATTACKING) {
            // TODO: If currently not tracking an enemy tower to attack, choose an enemy ruin to investigate
            if (targetEnemyRuin == null) {
                // assume vertical symmetry :TODO check for what type of symmetry it is
                MapLocation[] infos = rc.senseNearbyRuins(-1);

                // Check for allied tower, don't want to attack it,
                // TODO: ALSO for larger maps, there might not be enemy tower there yet, so scout it first for larger maps
                if (infos.length > 0) {
                    MapLocation ruin = infos[0];

                    MapLocation enemy = new MapLocation(ruin.x, rc.getMapHeight() - 1 - ruin.y);
                    targetEnemyRuin = enemy;
                }
            }

            if (targetEnemyRuin != null) {
                int dsquared = rc.getLocation().distanceSquaredTo(targetEnemyRuin);

                // Attack radius of soldier is 8; STRAT: attack then move away from enemy tower
                if (dsquared <= 8) {
                    // Attack enemy
                    if (rc.canAttack(targetEnemyRuin)) {
                        rc.attack(targetEnemyRuin);
                    }

                    // Move away from enemy ruin
                    Direction away = rc.getLocation().directionTo(targetEnemyRuin).opposite();
                    if (rc.canMove(away)) {
                        rc.move(away);
                        // TODO: Can be optimized to not just move left !
                    } else if (rc.canMove(away.rotateLeft())) {
                        rc.move(away.rotateLeft());
                    } else if (rc.canMove(away.rotateRight())) {
                        rc.move(away.rotateRight());
                    }
                } else {
                    // TODO: Check if adjacent tiles are within attack radius of tower
                    for (Direction dir : directions) {
                        MapLocation adjacent = rc.getLocation().add(dir);
                        if (adjacent.isWithinDistanceSquared(targetEnemyRuin, 8)) {
                            if (rc.canMove(dir)) {
                                rc.move(dir);

                                if (rc.canAttack(targetEnemyRuin)) {
                                    rc.attack(targetEnemyRuin);
                                }

                                break;
                            }
                        }
                    }

                    // Else too far away, so move closer towards enemy ruin with pathfinding algo
//                    bug2(rc, targetEnemyRuin);

                    rc.setIndicatorDot(targetEnemyRuin, 0, 0, 255);
                    rc.setIndicatorString("Moving to enemy ruin at " + targetEnemyRuin);
                }
            }
        }

        if (isMessenger) {
            rc.setIndicatorDot(rc.getLocation(), 0, 255, 0);
            updateFriendlyTowers(rc);
        }

        if (isMessenger && isSaving && knownTowers.size() > 0) {
            MapLocation dst = knownTowers.get(rng.nextInt(knownTowers.size()));
            Direction dir = rc.getLocation().directionTo(dst);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
        }


        Pair<MapInfo, Integer> result = getNearestRuin(rc);
        MapInfo curRuin = result.getLeft();
        if (curRuin != null){
            Direction dir = rc.getLocation().directionTo(curRuin.getMapLocation());
            MapLocation targetLoc = curRuin.getMapLocation();
            moveSoldier(rc, targetLoc);




            // Mark the pattern we need to draw to build a tower here if we haven't already.
            MapLocation shouldBeMarked = curRuin.getMapLocation().subtract(dir);
            if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                System.out.println("Trying to build a tower at " + targetLoc);
            }

            // Fill in any spots in the pattern with the appropriate paint.
            for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)){
                if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY){
                    boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(patternTile.getMapLocation()))
                        rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                }
            }

            // Complete the ruin if we can.
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                rc.setTimelineMarker("Tower built", 0, 255, 0);
                System.out.println("Built a tower at " + targetLoc + "!");
            }
        }

        // TODO: implement strategy for direction
        // Move and attack randomly if no objective.

        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        if (rc.canMove(dir) && rc.canAttack(nextLoc)) {
            rc.move(dir);
        }

        // Try to paint beneath us as we walk to avoid paint penalties.
        // Avoiding wasting paint by re-painting our own tiles.
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
            rc.attack(rc.getLocation());
        }
    }

    public static void runPaintPattern(RobotController rc) throws GameActionException {
        // Move in a circle around the ruin. Move every 3 turns after you have chance to paint some tiles
        if (paintingTurns % 3 == 0) {
            Direction toRuin = rc.getLocation().directionTo(paintingRuinLoc);
            Direction tangent = toRuin.rotateRight().rotateRight();
            int distance = rc.getLocation().distanceSquaredTo(paintingRuinLoc);

            if (distance > 4) {
                tangent = tangent.rotateLeft();
            }

            if (rc.canMove(tangent)) {
                rc.move(tangent);
            }
        }

        // use helper function to determine primar/secondary and paint a tile if possible
        if (rc.isActionReady()) {
            MapInfo[] infos = rc.senseNearbyMapInfos(3);
            boolean attacked = false;
            for(MapInfo info : infos){
                MapLocation loc = info.getMapLocation();
                boolean isSecondary = getIsSecondary(paintingRuinLoc, loc, paintingTowerType);
                if (rc.canAttack(loc)
                        && (info.getPaint() == PaintType.EMPTY || info.getPaint().isSecondary() != isSecondary)
                        && isWithinPattern(paintingRuinLoc, loc))
                    {
                        rc.attack(loc, isSecondary);
                        attacked = true;
                        turnsWithoutAttack = 0;
                        break;
                    }
            }

        }

        if (rc.canCompleteTowerPattern(paintingTowerType, paintingRuinLoc)) {
            rc.completeTowerPattern(paintingTowerType, paintingRuinLoc);
            state = RobotState.EXPLORING;
        }
        if (turnsWithoutAttack > 3) {
            state = RobotState.EXPLORING;
        }
    }


    public static UnitType getNewTowerType(RobotController rc) {
        // Optimal to build money towers if few towers
        if (rc.getNumberTowers() < 4)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        return rc.getNumberTowers() % 2 == 1 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public static boolean getIsSecondary(MapLocation ruinLoc, MapLocation paintLoc, UnitType towerType) {
        if (!isWithinPattern(ruinLoc, paintLoc)) return false;

        int col = paintLoc.x - ruinLoc.x + 2;
        int row = paintLoc.y - ruinLoc.y + 2;

        return towerType == UnitType.LEVEL_ONE_PAINT_TOWER ? paintTowerPattern[row][col] : moneyTowerPattern[row][col];
    }

    public static boolean isWithinPattern(MapLocation ruinLoc, MapLocation paintLoc) {
        return Math.abs(paintLoc.x - ruinLoc.x) <= 2 && Math.abs(paintLoc.y - ruinLoc.y) <= 2 && !ruinLoc.equals(paintLoc);
    }

    public static void runMopper(RobotController rc) throws GameActionException{

        // Move and attack randomly.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        moveSoldier(rc, nextLoc);
        if (rc.canMopSwing(dir)){
            rc.mopSwing(dir);
//            System.out.println("Mop Swing! Booyah!");
        }
        else if (rc.canAttack(nextLoc)){
            rc.attack(nextLoc);
        }

        // We can also move our code into different methods or classes to better organize it!
        updateEnemyRobots(rc);
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        int closestRuin = 1000000;
        Direction dir = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());

                if (dist < closestRuin) {
                    curRuin = tile;
                    closestRuin = dist;

                    dir = curRuin.getMapLocation().directionTo(rc.getLocation());

                    MapLocation target = curRuin.getMapLocation().subtract(dir);

                    if (rc.canAttack(target)) {
                        rc.attack(target);
                        System.out.println("HAHA a Splasher Attacked!!");

                    }
                }
            }
        }


        if (rc.canMove(dir)) {
            rc.move(dir);
        }

    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException{

        // Sensing methods can be passed in a radius of -1 to automatically
        // use the largest possible value.
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0){
            rc.setIndicatorString("There are nearby enemy robots! Scary!");
            // Save an array of locations with enemy robots in them for possible future use.
            MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
            for (int i = 0; i < enemyRobots.length; i++){
                enemyLocations[i] = enemyRobots[i].getLocation();
            }

            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());

            // Occasionally try to tell nearby allies how many enemy robots we see.
            if (rc.getRoundNum() % 20 == 0){
                for (RobotInfo ally : allyRobots){
                    if (rc.canSendMessage(ally.location, enemyRobots.length)){
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }

    public static Pair<MapInfo, Integer> getNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        int closestRuinDist = Integer.MAX_VALUE;
        Direction dir = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());

                if (dist < closestRuinDist) {
                    curRuin = tile;
                    closestRuinDist = dist;
                }
            }
        }
        return Pair.of(curRuin, closestRuinDist);
    }

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException{
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots){
            if (!robot.getType().isTowerType()) continue;

            MapLocation allyLoc = robot.getLocation();
            if (knownTowers.contains(allyLoc)){
                if (isSaving) {
                    if (rc.canSendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal())) {
                        rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                        isSaving = false;
                    }
                }

                continue;
            }

            knownTowers.add(allyLoc);
        }

    }
    public static void moveSoldier(RobotController rc, MapLocation loc) throws GameActionException {
        boolean moved = false;

        if (loc != null) {
            Direction dir = rc.getLocation().directionTo(loc);
            MapLocation newLoc = rc.getLocation().add(dir);
            boolean needsPainting = !rc.senseMapInfo(newLoc).getPaint().isAlly();

            if (needsPainting && rc.canMove(dir) && rc.canAttack(newLoc)) {
                rc.move(dir);
                checked.put(newLoc, 1);
                return;
            } else {
                for (Direction unit_dir : directions) {
                    if (rc.canMove(unit_dir) && rc.canAttack(newLoc)) {
                        needsPainting = !rc.senseMapInfo(rc.getLocation().add(unit_dir)).getPaint().isAlly();
                        newLoc = rc.getLocation().add(unit_dir);
                        if (needsPainting) {
                            rc.move(unit_dir);
                            checked.put(newLoc, 1);
                            return;
                        }

                    }
                }
            }
        }


        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation newLoc = rc.getLocation().add(dir);

        if (rc.canMove(dir) && !checked.containsKey(newLoc) && rc.canAttack(newLoc)) {
            checked.put(newLoc, 1);
            rc.move(dir);
            return;
        }

        for (Direction unit_dir : directions) {
            newLoc = rc.getLocation().add(dir);
            if (rc.canMove(dir) && rc.canAttack(newLoc)) {
                rc.move(dir);
                return;
            }
        }

    }
}
