package shoveguard;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Java Agent entry point.
 *
 * This file runs before Project Zomboid finishes loading. It watches for the
 * game's CombatManager class and injects two small checks into it:
 *
 * 1. attackCollisionCheck -> blocks the ranged-shove exploit against targets.
 * 2. processIsoWindow     -> blocks the same bad path when it hits windows.
 *
 * Most of the actual exploit detection lives in Hooks.java.
 */

public final class Agent {

    /**
     * Set to true while testing. Set to false for a quiet release build.
     */
    private static final boolean DEBUG = false;

    private static final String LOG_PREFIX = "[Shove Guard]";
    private static final String COMBAT_MANAGER_CLASS = "zombie/CombatManager";

    private Agent() {
        // Utility class. Do not instantiate.
    }

    /**
     * JVM calls this automatically when the jar is loaded with -javaagent.
     */
    public static void premain(String args, Instrumentation inst) {
        log("Agent loaded.");
        inst.addTransformer(new CombatManagerTransformer());
    }

    /**
     * Finds CombatManager and patches the methods we care about.
     */
    private static final class CombatManagerTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(
                Module module,
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
        ) {
            if (className == null || classfileBuffer == null) {
                return null;
            }

            if (!COMBAT_MANAGER_CLASS.equals(className)) {
                return null;
            }

            try {
                return patchCombatManager(classfileBuffer);
            } catch (Throwable t) {
                System.err.println(LOG_PREFIX + " Failed to patch CombatManager");
                t.printStackTrace();
                return null;
            }
        }

        private byte[] patchCombatManager(byte[] originalClassBytes) throws Exception {
            ClassPool pool = ClassPool.getDefault();
            CtClass combatManager = pool.makeClass(new ByteArrayInputStream(originalClassBytes));

            try {
                for (CtMethod method : combatManager.getDeclaredMethods()) {
                    patchMethodIfNeeded(method);
                }

                return combatManager.toBytecode();
            } finally {
                combatManager.detach();
            }
        }

        private void patchMethodIfNeeded(CtMethod method) throws Exception {
            String methodName = method.getName();

            if ("attackCollisionCheck".equals(methodName)) {
                log("Hooking CombatManager.attackCollisionCheck");
                method.insertBefore(
                        "{ if (shoveguard.Hooks.shouldCancelAttackCollision($1, $2, $3, $4)) return; }"
                );
                return;
            }

            if ("processIsoWindow".equals(methodName)) {
                log("Hooking CombatManager.processIsoWindow");
                method.insertBefore(
                        "{ if (shoveguard.Hooks.shouldCancelWindowHit($1, $2)) return; }"
                );
            }
        }
    }

    private static void log(String message) {
        if (DEBUG) {
            System.out.println(LOG_PREFIX + "[Debug] " + message);
        }
    }
}