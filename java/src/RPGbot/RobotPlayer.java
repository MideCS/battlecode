package RPGbot;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Random;


public class RobotPlayer {

    static int turnCount = 0;
    static int soldierCooldown = 0;
    static int mopperCooldown = 0;
    static boolean spawnedMopper = false;
    static HashMap<MapLocation, Integer> checked = new HashMap<>();
    static int checked_limit = 1;

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
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

//        int robotType = 0;

        if (soldierCooldown < 10) {
            if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)){
                rc.buildRobot(UnitType.SOLDIER, nextLoc);
//                System.out.println("BUILT A SOLDIER");
            }
        }
        else {
//            System.out.println("Soldier cooldown exceeded");
//            robotType = rng.nextInt(1,3);
            if (rc.canBuildRobot(UnitType.MOPPER, nextLoc) && mopperCooldown <= 4){
                rc.buildRobot(UnitType.MOPPER, nextLoc);
                System.out.println("BUILT A MOPPER");
                mopperCooldown ++;
            }
            else if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)){
                rc.buildRobot(UnitType.SPLASHER, nextLoc);
                rc.setIndicatorString("BUILT A SPLASHER");
                mopperCooldown = 0;
            }
        }

        soldierCooldown ++;
        soldierCooldown %= 40;

        // Read incoming messages
//        Message[] messages = rc.readMessages(-1);
//        for (Message m : messages) {
//            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());
//        }

        // TODO: can we attack other bots?
    }


    public static void runSoldier(RobotController rc) throws GameActionException{


        MapInfo curRuin = getNearestRuin(rc);
        boolean moved = false;

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

    public static MapInfo getNearestRuin(RobotController rc) throws GameActionException {
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
                }
            }
        }
        return curRuin;
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
