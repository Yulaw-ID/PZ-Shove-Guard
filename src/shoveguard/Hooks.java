package shoveguard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Exploit detection logic.
 *
 * Return value convention:
 * - false = allow the game to continue normally.
 * - true  = cancel this attack/window hit because it looks like the exploit.
 *
 * The core signature this patch looks for is:
 * Player is aiming + primary item is ranged + collision weapon is Base.BareHands.
 * That combination should not be used to hit targets from firearm range.
 */

public final class Hooks {

    /**
     * Set to true while testing. Set to false for a quiet release build.
     */
    private static final boolean DEBUG = false;

    private static final String LOG_PREFIX = "[Shove Guard]";
    private static final String BARE_HANDS_TYPE = "Base.BareHands";

    // Maximum legal distance for a close shove / gun-melee hit.
    private static final double MAX_MELEE_RANGE = 1.10;

    // Fallback scan settings for cases where the game has not assigned a direct target yet.
    private static final double FALLBACK_MAX_RANGE = 1.00;
    private static final int FALLBACK_SQUARE_RADIUS = 1;
    private static final double MIN_DOT_IN_FRONT = 0.35;

    // Window hit handling.
    private static final double WINDOW_POINT_BLANK_RANGE = 1.10;
    private static final double WINDOW_HARD_BLOCK_RANGE = 1.35;

    // Short shared block window. This prevents a bad far melee path from chaining
    // into a follow-up hit immediately after the first invalid attempt (i.e. a desync follow-up).
    private static final long BLOCK_WINDOW_MS = 400L;

    private static volatile long lastInvalidMeleeTime = 0L;

    private Hooks() {
        // Utility class. Do not instantiate.
    }

    // =========================================================
    // Hook 1: Attack Collision
    // =========================================================

    public static boolean shouldCancelAttackCollision(
            Object owner,
            Object weapon,
            Object swipeStatePlayer,
            Object attackTypeObj
    ) {
        try {
            if (owner == null) {
                return false;
            }

            AttackContext ctx = buildAttackContext(owner, weapon, attackTypeObj);

            debugAttackCollision(ctx, owner);

            if (!isSuspiciousRangedMelee(ctx)) {
                return false;
            }

            if (isWithinInvalidWindow()) {
                debug("[AttackCollision] Blocked by recent invalid melee timer.");
                return true;
            }

            Object target = safeCall(owner, "getAttackTarget");
            if (target != null && isRelevantLivingTarget(target)) {
                return shouldCancelDirectTargetHit(owner, target, ctx);
            }

            FallbackResult fallback = findClosestValidTarget(
                    owner,
                    FALLBACK_MAX_RANGE,
                    FALLBACK_SQUARE_RADIUS,
                    MIN_DOT_IN_FRONT
            );

            debug("[AttackCollision] Fallback scanned = " + fallback.scanned
                    + " candidates = " + fallback.candidates
                    + " bestDist = " + fallback.bestDist
                    + " best = " + describe(fallback.best));

            if (fallback.best != null) {
                return false;
            }

            markInvalidMelee("no_target_no_fallback");
            log("Blocked ranged-melee: no direct target and no valid close fallback target.");
            return true;

        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static boolean shouldCancelDirectTargetHit(Object owner, Object target, AttackContext ctx) {
        double dist = distance(owner, target);

        debug("[AttackCollision] Direct target = " + describe(target) + " Dist = " + dist);

        if (dist > MAX_MELEE_RANGE) {
            markInvalidMelee("direct_far");
            log("Blocked ranged-melee: direct target too far. Dist = " + dist
                    + " weapon = " + ctx.weaponType
                    + " primary = " + itemType(ctx.primary));
            return true;
        }

        if (!isInFront(owner, target, MIN_DOT_IN_FRONT)) {
            markInvalidMelee("direct_not_in_front");
            log("Blocked ranged-melee: direct target was not in front.");
            return true;
        }

        return false;
    }

    // =========================================================
    // Hook 2: Window Hit
    // =========================================================

    public static boolean shouldCancelWindowHit(Object player, Object hitInfo) {
        try {
            if (player == null || hitInfo == null) {
                return false;
            }

            Object weapon = safeCall(player, "getUseHandWeapon");
            Object attackTypeObj = safeCall(player, "getAttackType");
            AttackContext ctx = buildAttackContext(player, weapon, attackTypeObj);

            double distSq = getFloatField(hitInfo, "distSq");
            double dist = distSq > 0.0 ? Math.sqrt(distSq) : -1.0;
            Object target = safeCall(hitInfo, "getObject");

            debug("[Window] AttackType =" + ctx.attackType
                    + " aiming = " + ctx.aiming
                    + " primary = " + itemType(ctx.primary)
                    + " weapon = " + ctx.weaponType
                    + " suspicious = " + isSuspiciousRangedMelee(ctx)
                    + " dist = " + dist
                    + " target = " + describe(target)
                    + " timerAgeMs = " + timerAgeMs());

            if (!isSuspiciousRangedMelee(ctx)) {
                return false;
            }

            // Real point-blank interaction is allowed.
            if (dist >= 0.0 && dist <= WINDOW_POINT_BLANK_RANGE) {
                return false;
            }

            if (isWithinInvalidWindow()) {
                debug("[Window] Blocked by recent invalid melee timer.");
                return true;
            }

            if (dist >= 0.0 && dist > WINDOW_HARD_BLOCK_RANGE) {
                markInvalidMelee("window_far");
                debug("[Window] Blocked far window hit. Dist = " + dist);
                return true;
            }

            return false;

        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    // =========================================================
    // Attack Classification
    // =========================================================

    private static final class AttackContext {
        Object primary;
        Object weapon;
        boolean aiming;
        boolean primaryRanged;
        String weaponType;
        String attackType;
    }

    private static AttackContext buildAttackContext(Object owner, Object weapon, Object attackTypeObj) {
        AttackContext ctx = new AttackContext();
        ctx.weapon = weapon;
        ctx.primary = safeCall(owner, "getPrimaryHandItem");
        ctx.aiming = safeBoolean(owner, "isAiming");
        ctx.primaryRanged = ctx.primary != null && safeBoolean(ctx.primary, "isRanged");
        ctx.weaponType = weapon != null ? safeString(weapon, "getFullType") : "";
        ctx.attackType = enumName(attackTypeObj);
        return ctx;
    }

    private static boolean isSuspiciousRangedMelee(AttackContext ctx) {
        return ctx != null
                && ctx.aiming
                && ctx.primaryRanged
                && BARE_HANDS_TYPE.equals(ctx.weaponType);
    }

    // =========================================================
    // Short Block Timer
    // =========================================================

    private static void markInvalidMelee(String reason) {
        lastInvalidMeleeTime = System.currentTimeMillis();
        debug("[Timer] markInvalidMelee reason = " + reason + " at = " + lastInvalidMeleeTime);
    }

    private static boolean isWithinInvalidWindow() {
        long t = lastInvalidMeleeTime;
        return t != 0L && (System.currentTimeMillis() - t) < BLOCK_WINDOW_MS;
    }

    private static long timerAgeMs() {
        long t = lastInvalidMeleeTime;
        return t == 0L ? -1L : System.currentTimeMillis() - t;
    }

    // =========================================================
    // Fallback Target Scan
    // =========================================================

    private static final class FallbackResult {
        Object best;
        double bestDist = Double.MAX_VALUE;
        int scanned;
        int candidates;
    }

    private static FallbackResult findClosestValidTarget(
            Object player,
            double maxDist,
            int squareRadius,
            double minDot
    ) {
        FallbackResult result = new FallbackResult();

        try {
            Object square = safeCall(player, "getCurrentSquare");
            if (square == null) return result;

            Object cell = safeCall(square, "getCell");
            if (cell == null) return result;

            int px = (int) Math.floor(safeNumber(player, "getX"));
            int py = (int) Math.floor(safeNumber(player, "getY"));
            int pz = (int) Math.floor(safeNumber(player, "getZ"));

            for (int dx = -squareRadius; dx <= squareRadius; dx++) {
                for (int dy = -squareRadius; dy <= squareRadius; dy++) {
                    Object gridSquare = safeCall(cell, "getGridSquare", px + dx, py + dy, pz);
                    scanSquareForTargets(player, gridSquare, maxDist, minDot, result);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return result;
    }

    private static void scanSquareForTargets(
            Object player,
            Object gridSquare,
            double maxDist,
            double minDot,
            FallbackResult result
    ) {
        if (gridSquare == null) return;

        Object movingObjects = safeCall(gridSquare, "getMovingObjects");
        if (!(movingObjects instanceof List<?>)) return;

        for (Object obj : (List<?>) movingObjects) {
            result.scanned++;

            if (obj == null || obj == player) continue;
            if (!isRelevantLivingTarget(obj)) continue;

            double dist = distance(player, obj);
            if (dist > maxDist) continue;
            if (!isInFront(player, obj, minDot)) continue;

            result.candidates++;

            if (dist < result.bestDist) {
                result.bestDist = dist;
                result.best = obj;
            }
        }
    }

    // =========================================================
    // Target Filters
    // =========================================================

    private static boolean isRelevantLivingTarget(Object obj) {
        try {
            if (obj == null) return false;

            if (safeBoolean(obj, "isPlayer")) return true;
            if (safeBoolean(obj, "isZombie")) return true;

            String cls = obj.getClass().getName();
            String clsLower = cls.toLowerCase();

            // Extra coverage in case some entities do not expose isPlayer/isZombie cleanly.
            return cls.contains("IsoPlayer")
                    || cls.contains("IsoZombie")
                    || clsLower.contains("animal");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isInFront(Object player, Object target, double minDot) {
        double px = safeNumber(player, "getX");
        double py = safeNumber(player, "getY");
        double tx = safeNumber(target, "getX");
        double ty = safeNumber(target, "getY");

        double dx = tx - px;
        double dy = ty - py;

        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001) return true;

        dx /= len;
        dy /= len;

        double fx = safeNumber(player, "getForwardDirectionX");
        double fy = safeNumber(player, "getForwardDirectionY");

        if (Math.abs(fx) < 0.0001 && Math.abs(fy) < 0.0001) {
            Object forward = safeCall(player, "getForwardDirection");
            if (forward != null) {
                fx = safeNumber(forward, "x");
                fy = safeNumber(forward, "y");
            }
        }

        double forwardLen = Math.sqrt(fx * fx + fy * fy);
        if (forwardLen < 0.0001) return true;

        fx /= forwardLen;
        fy /= forwardLen;

        double dot = dx * fx + dy * fy;
        return dot >= minDot;
    }

    // =========================================================
    // Reflection Helpers
    // =========================================================

    private static Object safeCall(Object o, String name, Object... args) {
        if (o == null) return null;

        for (Method method : o.getClass().getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (method.getParameterCount() != args.length) continue;

            try {
                return method.invoke(o, args);
            } catch (Throwable ignored) {
                // Missing or incompatible game internals should not crash the server.
            }
        }

        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> current = cls;

        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private static float getFloatField(Object o, String name) {
        if (o == null) return 0f;

        try {
            Field field = findField(o.getClass(), name);
            if (field == null) return 0f;

            field.setAccessible(true);
            return field.getFloat(o);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static boolean safeBoolean(Object o, String name) {
        Object value = safeCall(o, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static String safeString(Object o, String name) {
        Object value = safeCall(o, name);
        return value == null ? "" : String.valueOf(value);
    }

    private static double safeNumber(Object o, String name) {
        if (o == null) return 0.0;

        Object value = safeCall(o, name);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        try {
            Field field = findField(o.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                Object fieldValue = field.get(o);
                if (fieldValue instanceof Number) {
                    return ((Number) fieldValue).doubleValue();
                }
            }
        } catch (Throwable ignored) {
            // Default to 0.0 below.
        }

        return 0.0;
    }

    // =========================================================
    // Small Utility Methods
    // =========================================================

    private static double distance(Object a, Object b) {
        double ax = safeNumber(a, "getX");
        double ay = safeNumber(a, "getY");
        double bx = safeNumber(b, "getX");
        double by = safeNumber(b, "getY");

        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String enumName(Object o) {
        if (o == null) return "";
        if (o instanceof Enum<?>) {
            return ((Enum<?>) o).name();
        }
        return String.valueOf(o);
    }

    private static String itemType(Object item) {
        if (item == null) return "null";
        String type = safeString(item, "getFullType");
        return type.isEmpty() ? item.getClass().getName() : type;
    }

    private static String describe(Object o) {
        if (o == null) return "null";

        return o.getClass().getName()
                + " (" + safeNumber(o, "getX")
                + "," + safeNumber(o, "getY")
                + "," + safeNumber(o, "getZ") + ")";
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }

    private static void debug(String message) {
        if (DEBUG) {
            System.out.println(LOG_PREFIX + "[Debug] " + message);
        }
    }

    private static void debugAttackCollision(AttackContext ctx, Object owner) {
        if (!DEBUG) return;

        Object target = safeCall(owner, "getAttackTarget");
        double targetDist = target != null ? distance(owner, target) : -1.0;

        debug("[AttackCollision] Aiming = " + ctx.aiming
                + " primary = " + itemType(ctx.primary)
                + " weapon = " + ctx.weaponType
                + " attackType = " + ctx.attackType
                + " suspicious = " + isSuspiciousRangedMelee(ctx)
                + " timerAgeMs = " + timerAgeMs()
                + " target = " + describe(target)
                + " targetDist = " + targetDist);
    }
}